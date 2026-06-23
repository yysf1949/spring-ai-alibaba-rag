package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.quota.TenantQuota;
import io.github.yysf1949.rag.agent.quota.TenantQuotaEnforcer;
import io.github.yysf1949.rag.agent.quota.TenantTier;
import io.github.yysf1949.rag.agent.quota.UsageMeter;
import io.github.yysf1949.rag.agent.quota.store.InMemoryTenantQuotaRepository;
import io.github.yysf1949.rag.agent.quota.store.InMemoryUsageMeter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link TenantQuotaController} 测试 — MockMvc standalone 模式.
 *
 * <p>覆盖: GET 查 / PUT 覆盖 / 列出全部 / 清除降级 / 异常映射.
 * 走 standaloneSetup, 不启动 Spring 上下文, 跟 FeedbackControllerTest 风格一致.</p>
 */
class TenantQuotaControllerTest {

    private InMemoryTenantQuotaRepository repo;
    private InMemoryUsageMeter meter;
    private TenantQuotaEnforcer enforcer;
    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        repo = new InMemoryTenantQuotaRepository();
        meter = new InMemoryUsageMeter();
        enforcer = new TenantQuotaEnforcer(repo, meter, new SimpleMeterRegistry());
        mvc = MockMvcBuilders.standaloneSetup(new TenantQuotaController(repo, enforcer))
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
    }

    @Test
    void getAutoProvisionsQuotaAndReturnsSnapshot() throws Exception {
        mvc.perform(get("/api/admin/tenant-quota/{id}", "tenant-new"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tenantId").value("tenant-new"))
                .andExpect(jsonPath("$.tier").value("FREE"))
                .andExpect(jsonPath("$.monthlyCallLimit").value(1000))
                .andExpect(jsonPath("$.monthlyTokenLimit").value(100000));
    }

    @Test
    void getExistingQuotaReturnsConfiguredTier() throws Exception {
        long now = System.currentTimeMillis();
        repo.save(TenantQuota.forNewTenant("t-pro", TenantTier.PRO, now));

        mvc.perform(get("/api/admin/tenant-quota/{id}", "t-pro"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("PRO"))
                .andExpect(jsonPath("$.monthlyCallLimit").value(50000));
    }

    @Test
    void putOverridesQuota() throws Exception {
        String body = """
                {
                  "tier": "ENTERPRISE",
                  "monthlyCallLimit": 5000000,
                  "monthlyTokenLimit": 1000000000
                }
                """;

        mvc.perform(put("/api/admin/tenant-quota/{id}", "t-ent")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("ENTERPRISE"))
                .andExpect(jsonPath("$.monthlyCallLimit").value(5000000))
                .andExpect(jsonPath("$.monthlyTokenLimit").value(1000000000));

        TenantQuota saved = repo.findById("t-ent").orElseThrow();
        assertThat(saved.tier()).isEqualTo(TenantTier.ENTERPRISE);
    }

    @Test
    void putWithoutTierDefaultsToFree() throws Exception {
        // tier 字段缺失 → tier 走默认值 FREE (避免 NPE)
        String body = """
                {
                  "monthlyCallLimit": 500
                }
                """;

        mvc.perform(put("/api/admin/tenant-quota/{id}", "t-default")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tier").value("FREE"));
    }

    @Test
    void putRejectsNegativeLimit() throws Exception {
        String body = """
                {
                  "tier": "PRO",
                  "monthlyCallLimit": -1
                }
                """;

        mvc.perform(put("/api/admin/tenant-quota/{id}", "t-bad")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void putOverridesTierAndClearsDowngrade() throws Exception {
        // PUT 一个新 tier (非 FREE) 在降级态上 → 视作 admin 显式覆盖, 降级被清除
        long now = System.currentTimeMillis();
        TenantQuota pre = TenantQuota.forNewTenant("t-down", TenantTier.PRO, now);
        repo.save(pre.markedDowngraded(now + 100L));
        // 现在: tier=FREE, originalTier=PRO, downgradedAt=now+100

        String body = """
                {
                  "tier": "ENTERPRISE"
                }
                """;
        mvc.perform(put("/api/admin/tenant-quota/{id}", "t-down")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                // 降级被清除
                .andExpect(jsonPath("$.downgradedAt").doesNotExist());

        TenantQuota after = repo.findById("t-down").orElseThrow();
        assertThat(after.tier()).isEqualTo(TenantTier.ENTERPRISE);
        assertThat(after.downgradedAt()).isNull();
        assertThat(after.originalTier()).isNull();
    }

    @Test
    void putKeepsDowngradeWhenTierStaysFree() throws Exception {
        // PUT tier=FREE 在降级态上 → 不清除降级 (只是改 limit), 让 enforcer 继续压制
        long now = System.currentTimeMillis();
        TenantQuota pre = TenantQuota.forNewTenant("t-stay-down", TenantTier.PRO, now);
        repo.save(pre.markedDowngraded(now + 100L));

        String body = """
                {
                  "tier": "FREE",
                  "monthlyCallLimit": 50
                }
                """;
        mvc.perform(put("/api/admin/tenant-quota/{id}", "t-stay-down")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        TenantQuota after = repo.findById("t-stay-down").orElseThrow();
        assertThat(after.downgradedAt()).isEqualTo(now + 100L);  // 保留
        assertThat(after.tier()).isEqualTo(TenantTier.FREE);
        assertThat(after.originalTier()).isEqualTo(TenantTier.PRO);
    }

    @Test
    void clearDowngradeResetsDowngradedAt() throws Exception {
        long now = System.currentTimeMillis();
        TenantQuota pre = TenantQuota.forNewTenant("t-clear", TenantTier.PRO, now);
        repo.save(pre.markedDowngraded(now + 100L));

        mvc.perform(put("/api/admin/tenant-quota/{id}/clear-downgrade", "t-clear"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.downgradedAt").doesNotExist());

        TenantQuota after = repo.findById("t-clear").orElseThrow();
        assertThat(after.downgradedAt()).isNull();
    }

    @Test
    void listReturnsAllTenants() throws Exception {
        long now = System.currentTimeMillis();
        repo.save(TenantQuota.forNewTenant("t1", TenantTier.FREE, now));
        repo.save(TenantQuota.forNewTenant("t2", TenantTier.PRO, now));

        mvc.perform(get("/api/admin/tenant-quota"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
    }

    @Test
    void exceededQuotaMapsTo429WithProblemDetail() throws Exception {
        // 直接走 handler 验证映射 (Standalone MockMvc 不会真的触发 controller 抛这个异常,
        // 因为 controller 内不调 execute — execute 是给业务方调的. 这里用直接构造异常的方式验证映射.
        String monthKey = TenantQuota.currentMonthKey();
        var ex = new io.github.yysf1949.rag.agent.quota.TenantQuotaExceededException(
                "t-fail", "calls", 5L, 2L, monthKey);
        org.springframework.http.ProblemDetail pd = new AgentExceptionHandler()
                .handleTenantQuotaExceeded(ex);

        assertThat(pd.getStatus()).isEqualTo(429);
        assertThat(pd.getProperties()).containsEntry("tenantId", "t-fail");
        assertThat(pd.getProperties()).containsEntry("resource", "calls");
        assertThat(pd.getProperties()).containsEntry("currentUsage", 5L);
        assertThat(pd.getProperties()).containsEntry("limit", 2L);
    }

    @Test
    void meterSnapshotReflectsUsageAcrossResources() throws Exception {
        long now = System.currentTimeMillis();
        repo.save(TenantQuota.forNewTenant("t-snap", TenantTier.PRO, now));
        for (int i = 0; i < 3; i++) {
            enforcer.execute("t-snap", UsageMeter.RESOURCE_CALLS, 1L, () -> null);
        }
        enforcer.execute("t-snap", UsageMeter.RESOURCE_TOKENS, 1000L, () -> null);

        mvc.perform(get("/api/admin/tenant-quota/{id}", "t-snap"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.currentCalls").value(3))
                .andExpect(jsonPath("$.currentTokens").value(1000))
                .andExpect(jsonPath("$.callUsageRatio").value(0.00006));   // 3/50000
    }
}