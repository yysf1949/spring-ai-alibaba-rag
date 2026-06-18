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
import io.github.yysf1949.rag.agent.builtin.InMemoryTicketRepository;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.TicketTool;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import io.github.yysf1949.rag.agent.handoff.HumanReviewQueue;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.github.yysf1949.rag.core.port.QAService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAgentLoopTest {

    private DefaultAgentLoop loop;
    private ToolRegistry registry;
    private List<ToolInvocationContext> auditTrail;
    private MeterRegistry meterRegistry;

    // ── 测试工具 payload 类型 ──────────────────────────────────

    record HugePayload(long amountCents) { }
    record SimplePayload() { }

    // ── 测试工具 bean ──────────────────────────────────────────

    static class TestTools {
        @ToolSpec(
                name = "huge_refund",
                description = "Test tool with L3 amount limit",
                riskLevel = RiskLevel.L3_BUSINESS_STATE,
                idempotent = true,
                requiresIdempotencyKey = true,
                maxAmountCents = 100_00)
        public String hugeRefund(HugePayload p) {
            return "refunded";
        }

        @ToolSpec(
                name = "admin_tool",
                description = "Test L4 tool",
                riskLevel = RiskLevel.L4_HIGH_RISK,
                idempotent = true,
                requiresIdempotencyKey = true)
        public String adminTool(SimplePayload p) {
            return "admin-ok";
        }

        @ToolSpec(
                name = "simple_tool",
                description = "Test L1 tool",
                riskLevel = RiskLevel.L1_READ,
                idempotent = true,
                requiresIdempotencyKey = false)
        public String simpleTool(SimplePayload p) {
            return "ok";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        QAService qa = mock(QAService.class);
        when(qa.answer(any(Query.class))).thenReturn(new Answer(
                "t1", "qh", "怎么退款",
                List.of(), List.of(),
                "退款政策：7 天无理由", List.of(),
                AnswerSource.LLM, 0L, java.util.Map.of()));

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(QAService.class, () -> qa);
            ctx.register(KbSearchTool.class, TicketTool.class, InMemoryTicketRepository.class,
                    InMemoryIdempotencyStore.class, DefaultRiskGate.class,
                    io.github.yysf1949.rag.agent.action.InMemoryToolRegistry.class);
            ctx.refresh();
            registry = ctx.getBean(io.github.yysf1949.rag.agent.action.InMemoryToolRegistry.class);
            registry.scanFromContext(ctx);
        }

        // Register test tools via reflection into registry's map
        TestTools testTools = new TestTools();
        Field descriptorsField = registry.getClass().getDeclaredField("descriptors");
        descriptorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) descriptorsField.get(registry);
        for (Method m : TestTools.class.getMethods()) {
            ToolSpec spec = m.getAnnotation(ToolSpec.class);
            if (spec == null) continue;
            ToolDescriptor desc = new ToolDescriptor(
                    spec.name(), spec.description(), spec.riskLevel(),
                    spec.idempotent(), spec.requiresIdempotencyKey(),
                    spec.maxAmountCents() >= 0 ? spec.maxAmountCents() : null,
                    testTools, m);
            map.put(spec.name(), desc);
        }

        auditTrail = new ArrayList<>();
        ToolAuditBridge bridge = new ToolAuditBridge(new LlmAuditHook() {
            @Override public void onLlmCall(String t, String u, String s, String q, String m, String pt, String pb, String c, long l, String o) {
                auditTrail.add(new ToolInvocationContext(new AgentIdentity(t, u, s, Set.of()), m, pb, c, l, o));
            }
        });
        IdempotencyStore idem = new InMemoryIdempotencyStore();
        RiskGate gate = new DefaultRiskGate();
        meterRegistry = new SimpleMeterRegistry();
        AgentMetrics agentMetrics = new AgentMetrics(meterRegistry);
        HumanReviewQueue reviewQueue = new HumanReviewQueue();
        HandoffService handoffService = new HandoffService(reviewQueue, agentMetrics);
        ObjectMapper objectMapper = new ObjectMapper();
        loop = new DefaultAgentLoop(registry, gate, idem, bridge, agentMetrics, handoffService, objectMapper);
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private static AgentIdentity identity(String userId, String tenantId, String sessionId) {
        return new AgentIdentity(tenantId, userId, sessionId, Set.of("user"));
    }

    private static AgentIdentity identity(String userId, String tenantId, String sessionId, List<String> roles) {
        return new AgentIdentity(tenantId, userId, sessionId, Set.copyOf(roles));
    }

    // ── 已有测试（Phase 9）────────────────────────────────────

    @Test
    void executesToolByName() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var req = AgentRequest.of(identity, "kb_search",
                new KbSearchTool.Request("t1", "u1", "怎么退款", Set.of(), 5, null),
                null);
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        assertThat(resp.toolName()).isEqualTo("kb_search");
        assertThat(auditTrail).hasSize(1);
        assertThat(auditTrail.get(0).outcome()).isEqualTo("SUCCESS");
    }

    @Test
    void writeToolRequiresIdempotencyKey() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var req = AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "请人工跟进"),
                null); // 没有 idempotencyKey
        // Phase 10: execute() 不再抛异常，返回 DENIED
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.DENIED);
    }

    @Test
    void writeToolReplaysOnSameKey() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "tok-A");
        var req1 = AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "first"), key);
        var req2 = AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "second"), key);
        var r1 = loop.execute(req1);
        var r2 = loop.execute(req2);
        assertThat(r1.toolResponse()).isEqualTo(r2.toolResponse()); // same ticket id
    }

    // ── Phase 10: handoff 分流 + metrics 埋点 ────────────────────────

    @Test
    void amountLimitExceededTriggersHandoff() {
        // 注册一个 L3 工具 — 100 元上限，调用 500 元触发 AmountLimitExceeded
        var idem = IdempotencyKey.of("t1", "user-1", "s1", "huge_refund", "huge-1");
        var req = new AgentRequest(identity("user-1", "t1", "s1"),
                "huge_refund", new HugePayload(500_00L), idem,
                AgentChannel.HTTP, false);

        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext()).isNotNull();
        assertThat(resp.handoffContext().reason()).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    void l4WithoutAdminTriggersHandoff() {
        var idem = IdempotencyKey.of("t1", "user-1", "s1", "admin_tool", "admin-1");
        // user role 触发 L4 admin 拒绝
        var req = new AgentRequest(identity("user-1", "t1", "s1", List.of("user")),
                "admin_tool", new SimplePayload(), idem,
                AgentChannel.HTTP, false);

        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext()).isNotNull();
        assertThat(resp.handoffContext().reason()).isEqualTo("INSUFFICIENT_PRIVILEGE");
    }

    @Test
    void recordsInvocationMetric() {
        // 验证 AgentMetrics 收到调用埋点
        var req = new AgentRequest(identity("user-1", "t1", "s1"),
                "simple_tool", new SimplePayload(), null,
                AgentChannel.HTTP, false);

        loop.execute(req);

        // 用 metrics registry 验证埋点
        double invocations = meterRegistry.counter("agent.tool.invocations",
                "tool", "simple_tool", "outcome", "SUCCESS").count();
        assertThat(invocations).isEqualTo(1.0);
    }
}
