package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.port.PriceProtectionPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryPriceProtectionRepository;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PriceProtectionToolTest {

    private InMemoryPriceProtectionRepository repo;
    private PriceProtectionTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryPriceProtectionRepository();
        tool = new PriceProtectionTool(repo, repo);
    }

    // ==================== L1 查询 ====================

    @Test
    void queryPolicyReturnsDefault() {
        var resp = tool.queryPolicy(new PriceProtectionTool.QueryPolicyRequest(
                "tenant-1", "user-1", "electronics"));
        assertThat(resp.protectionDays()).isEqualTo(7);
        assertThat(resp.maxRefundRatio()).isEqualTo(1.0);
    }

    @Test
    void checkEligibilityWithinPeriod() {
        var now = Instant.now().minusSeconds(3 * 86400); // 3 天前
        var resp = tool.checkEligibility(new PriceProtectionTool.EligibilityRequest(
                "tenant-1", "user-1", "ORD-1", now.toString(), "electronics"));
        assertThat(resp.eligible()).isTrue();
        assertThat(resp.message()).contains("价保期内");
    }

    @Test
    void checkEligibilityExpired() {
        var old = Instant.now().minusSeconds(10 * 86400); // 10 天前
        var resp = tool.checkEligibility(new PriceProtectionTool.EligibilityRequest(
                "tenant-1", "user-1", "ORD-1", old.toString(), "electronics"));
        assertThat(resp.eligible()).isFalse();
        assertThat(resp.message()).contains("超过价保期限");
    }

    // ==================== L3 写操作 ====================

    @Test
    void applyPriceProtectionHappyPath() {
        var now = Instant.now().minusSeconds(3 * 86400);
        var resp = tool.applyPriceProtection(new PriceProtectionTool.ApplyRequest(
                "tenant-1", "user-1", "ORD-1", "SKU-1",
                50_00L, 200_00L, 150_00L,
                now.toString(), "electronics", "降价申请", "key-1"));
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(resp.claimId()).startsWith("PP-");
        assertThat(resp.refundAmountCents()).isEqualTo(50_00L);
    }

    @Test
    void applyPriceProtectionExceedsAmountGate() {
        var now = Instant.now().minusSeconds(3 * 86400);
        assertThatThrownBy(() -> tool.applyPriceProtection(new PriceProtectionTool.ApplyRequest(
                "tenant-1", "user-1", "ORD-1", "SKU-1",
                300_00L, 500_00L, 200_00L,
                now.toString(), "electronics", "降价", "key-1")))
                .isInstanceOf(AmountLimitExceededException.class)
                .hasMessageContaining("30000")
                .hasMessageContaining("20000");
    }

    @Test
    void applyPriceProtectionExceedsPolicyRatio() {
        var now = Instant.now().minusSeconds(3 * 86400);
        // 原价 100 元，申请退 200 元差价 — 超过 maxRefundRatio=1.0 的限制
        assertThatThrownBy(() -> tool.applyPriceProtection(new PriceProtectionTool.ApplyRequest(
                "tenant-1", "user-1", "ORD-1", "SKU-1",
                200_00L, 100_00L, 0L,
                now.toString(), "electronics", "降价", "key-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exceeds policy max");
    }

    @Test
    void applyPriceProtectionOutsidePeriod() {
        var old = Instant.now().minusSeconds(10 * 86400);
        assertThatThrownBy(() -> tool.applyPriceProtection(new PriceProtectionTool.ApplyRequest(
                "tenant-1", "user-1", "ORD-1", "SKU-1",
                50_00L, 200_00L, 150_00L,
                old.toString(), "electronics", "降价", "key-1")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the price protection period");
    }

    @Test
    void applyPriceProtectionIdempotentSameKey() {
        var now = Instant.now().minusSeconds(3 * 86400);
        var req = new PriceProtectionTool.ApplyRequest(
                "tenant-1", "user-1", "ORD-1", "SKU-1",
                50_00L, 200_00L, 150_00L,
                now.toString(), "electronics", "降价", "same-key");
        var resp1 = tool.applyPriceProtection(req);
        var resp2 = tool.applyPriceProtection(req);
        // 两次返回相同 claimId（InMemory 存储相同 key 会覆盖，但返回结果一致）
        assertThat(resp1.claimId()).isEqualTo(resp2.claimId());
        assertThat(resp1.refundAmountCents()).isEqualTo(resp2.refundAmountCents());
    }
}