package io.github.yysf1949.rag.agent.governance;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 业务指标增强测试 — 覆盖 AgentMetrics 新增的 4 个业务指标方法。
 */
class AgentMetricsEnhancedTest {

    private MeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
    }

    @Test
    void recordBusinessError_incrementsCounter() {
        metrics.recordBusinessError("cancel_order", "ORDER_NOT_FOUND");
        double count = registry.counter("agent.tool.business_errors",
                "tool", "cancel_order", "errorType", "ORDER_NOT_FOUND").count();
        assertThat(count).isEqualTo(1.0);

        metrics.recordBusinessError("cancel_order", "ORDER_NOT_FOUND");
        assertThat(registry.counter("agent.tool.business_errors",
                "tool", "cancel_order", "errorType", "ORDER_NOT_FOUND").count()).isEqualTo(2.0);
    }

    @Test
    void recordBusinessError_multipleErrorTypes() {
        metrics.recordBusinessError("create_refund", "AMOUNT_EXCEEDED");
        metrics.recordBusinessError("create_refund", "IDEMPOTENCY_CONFLICT");

        assertThat(registry.counter("agent.tool.business_errors",
                "tool", "create_refund", "errorType", "AMOUNT_EXCEEDED").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.tool.business_errors",
                "tool", "create_refund", "errorType", "IDEMPOTENCY_CONFLICT").count()).isEqualTo(1.0);
    }

    @Test
    void recordConfirmation_confirmedTrue() {
        metrics.recordConfirmation("cancel_order", true);
        double count = registry.counter("agent.tool.confirmations",
                "tool", "cancel_order", "confirmed", "true").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordConfirmation_confirmedFalse() {
        metrics.recordConfirmation("cancel_order", false);
        double count = registry.counter("agent.tool.confirmations",
                "tool", "cancel_order", "confirmed", "false").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordConfirmation_separateCountersPerTool() {
        metrics.recordConfirmation("cancel_order", true);
        metrics.recordConfirmation("create_refund", false);

        assertThat(registry.counter("agent.tool.confirmations",
                "tool", "cancel_order", "confirmed", "true").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.tool.confirmations",
                "tool", "cancel_order", "confirmed", "false").count()).isEqualTo(0.0);
        assertThat(registry.counter("agent.tool.confirmations",
                "tool", "create_refund", "confirmed", "false").count()).isEqualTo(1.0);
    }

    @Test
    void recordRollback_incrementsCounter() {
        metrics.recordRollback("cancel_order", "user_cancelled");
        double count = registry.counter("agent.tool.rollbacks",
                "tool", "cancel_order", "reason", "user_cancelled").count();
        assertThat(count).isEqualTo(1.0);

        metrics.recordRollback("cancel_order", "user_cancelled");
        assertThat(registry.counter("agent.tool.rollbacks",
                "tool", "cancel_order", "reason", "user_cancelled").count()).isEqualTo(2.0);
    }

    @Test
    void recordHandoffQuality_withContext() {
        metrics.recordHandoffQuality(true, "HANDOFF_REQUIRED");
        double count = registry.counter("agent.conversation.handoff_quality",
                "hasContext", "true", "handoffReason", "HANDOFF_REQUIRED").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordHandoffQuality_withoutContext() {
        metrics.recordHandoffQuality(false, "HANDOFF_REQUIRED");
        double count = registry.counter("agent.conversation.handoff_quality",
                "hasContext", "false", "handoffReason", "HANDOFF_REQUIRED").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordHandoffQuality_separateCountersPerReason() {
        metrics.recordHandoffQuality(true, "HANDOFF_REQUIRED");
        metrics.recordHandoffQuality(true, "AMOUNT_EXCEEDED");
        metrics.recordHandoffQuality(false, "HANDOFF_REQUIRED");

        assertThat(registry.counter("agent.conversation.handoff_quality",
                "hasContext", "true", "handoffReason", "HANDOFF_REQUIRED").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.conversation.handoff_quality",
                "hasContext", "true", "handoffReason", "AMOUNT_EXCEEDED").count()).isEqualTo(1.0);
        assertThat(registry.counter("agent.conversation.handoff_quality",
                "hasContext", "false", "handoffReason", "HANDOFF_REQUIRED").count()).isEqualTo(1.0);
    }
}
