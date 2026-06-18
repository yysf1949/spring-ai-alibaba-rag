package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.ConfirmationService;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.TenantRateLimiter;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import io.github.yysf1949.rag.agent.handoff.HumanReviewQueue;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 13b M6 集成测试 — 当 Tool 抛 {@link HandoffRequiredException}，
 * {@link DefaultAgentLoop} 必须自动走 handoff 分流（不视为 FAILURE）。
 *
 * <p>模式：手动注册抛异常的 Tool（参考 DefaultAgentLoopTest 的反射注入方式），
 * 然后断言 outcome=HANDOFF_REQUIRED + handoffContext 携带完整前置工作证据。</p>
 */
class DefaultAgentLoopHandoffTest {

    /** 抛 HandoffRequiredException 的 Tool bean — 模拟"业务规则命中"。 */
    static class RuleMandatingTool {
        @ToolSpec(name = "rule_mandating_tool",
                description = "Tool that mandates human review",
                riskLevel = RiskLevel.L3_BUSINESS_STATE,
                idempotent = false,
                requiresIdempotencyKey = true)
        public String invoke(Request req) {
            throw new HandoffRequiredException(
                    "rule_mandating_tool",
                    "combo_coupon_requires_manual",
                    List.of("has_combo_coupon", "refund_window_exceeded"),
                    "Test order hits combo_coupon + window_exceeded; manual review per policy.");
        }
        public record Request(String tenantId, String userId, String orderId) { }
    }

    private DefaultAgentLoop loop;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        var registry = new io.github.yysf1949.rag.agent.action.InMemoryToolRegistry();

        // 反射注入 descriptors (参考 DefaultAgentLoopTest 既有 pattern)
        Field descriptorsField = registry.getClass().getDeclaredField("descriptors");
        descriptorsField.setAccessible(true);
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) descriptorsField.get(registry);

        var testTools = new RuleMandatingTool();
        Method m = RuleMandatingTool.class.getMethod("invoke", RuleMandatingTool.Request.class);
        ToolSpec spec = m.getAnnotation(ToolSpec.class);
        ToolDescriptor desc = new ToolDescriptor(
                spec.name(), spec.description(), spec.riskLevel(),
                spec.idempotent(), spec.requiresIdempotencyKey(),
                spec.maxAmountCents() >= 0 ? spec.maxAmountCents() : null,
                spec.requiresConfirmationToken(),
                testTools, m);
        map.put(spec.name(), desc);

        LlmAuditHook hook = (t, u, s, q, mId, pt, pb, c, l, o) -> { };
        ToolAuditBridge bridge = new ToolAuditBridge(hook);
        var idem = new InMemoryIdempotencyStore();
        RiskGate gate = new DefaultRiskGate(new ConfirmationService());
        var metrics = new AgentMetrics(new SimpleMeterRegistry());
        var reviewQueue = new HumanReviewQueue();
        HandoffService handoffService = new HandoffService(reviewQueue, metrics);
        TenantRateLimiter tenantLimiter = new TenantRateLimiter();
        ObjectMapper mapper = new ObjectMapper();

        loop = new DefaultAgentLoop(registry, gate, idem, bridge, metrics,
                handoffService, tenantLimiter, mapper);
    }

    @Test
    void handoffRequiredExceptionAutoTriggersHandoff() {
        AgentIdentity identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        IdempotencyKey key = IdempotencyKey.of("t1", "u1", "s1", "rule_mandating_tool", "tok-1");
        var req = new AgentRequest(identity, "rule_mandating_tool",
                new RuleMandatingTool.Request("t1", "u1", "O-1"),
                key, AgentChannel.HTTP, false);

        AgentResponse resp = loop.execute(req);

        // 关键断言: 业务规则命中 → 自动 HANDOFF_REQUIRED,不是 FAILURE
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext()).isNotNull();
        assertThat(resp.handoffContext().reason()).isEqualTo("BUSINESS_RULE_MANDATES_HUMAN");
    }

    @Test
    void handoffContextCarriesRuleReasonInMessage() {
        AgentIdentity identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        IdempotencyKey key = IdempotencyKey.of("t1", "u1", "s1", "rule_mandating_tool", "tok-2");
        var req = new AgentRequest(identity, "rule_mandating_tool",
                new RuleMandatingTool.Request("t1", "u1", "O-2"),
                key, AgentChannel.HTTP, false);

        AgentResponse resp = loop.execute(req);

        // 验证 message 包含规则 reason (文章要求"前置工作证据"传到人工侧)
        assertThat(resp.message()).contains("combo_coupon_requires_manual");
        assertThat(resp.outcome()).isNotEqualTo(AgentOutcome.FAILURE);
    }

    @Test
    void handoffQueueReceivesToolChainMarker() {
        AgentIdentity identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        IdempotencyKey key = IdempotencyKey.of("t1", "u1", "s1", "rule_mandating_tool", "tok-3");
        var req = new AgentRequest(identity, "rule_mandating_tool",
                new RuleMandatingTool.Request("t1", "u1", "O-3"),
                key, AgentChannel.HTTP, false);

        AgentResponse resp = loop.execute(req);

        // 验证 handoffContext 含 toolChainJson + WORK_ORDER 渠道
        assertThat(resp.handoffContext()).isNotNull();
        assertThat(resp.handoffContext().toolChainJson()).isEqualTo("rule_mandating_tool");
        assertThat(resp.handoffContext().nextChannel()).isEqualTo("WORK_ORDER");
    }
}