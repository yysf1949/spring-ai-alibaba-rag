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
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.TicketTool;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryTicketRepository;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.ConfirmationService;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import io.github.yysf1949.rag.agent.handoff.HumanReviewQueue;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AgentLoop 调试/追踪模式测试。
 *
 * <p>验证 {@link DebugMode} 各模式下 {@link DefaultAgentLoop} 的行为：
 * <ul>
 *   <li>OFF 模式: debugTrace 为空</li>
 *   <li>RECORD 模式: debugTrace 记录了所有步骤</li>
 *   <li>VERBOSE 模式: 每步都有 stderr 输出</li>
 *   <li>完整链路: 查询订单 → 退款 → 确认，追踪包含 4 个阶段</li>
 * </ul>
 */
class DefaultAgentLoopDebugTest {

    private DefaultAgentLoop loop;
    private ToolRegistry registry;

    // ── 测试工具 payload 类型 ──────────────────────────────────

    record OrderQueryPayload(String orderId) { }
    record RefundPayload(String orderId, long amountCents) { }
    record ConfirmPayload(String refundId) { }

    // ── 测试工具 bean ──────────────────────────────────────────

    static class CustomerServiceTools {
        @ToolSpec(
                name = "query_order",
                description = "查询订单详情",
                riskLevel = RiskLevel.L1_READ,
                idempotent = true,
                requiresIdempotencyKey = false)
        public String queryOrder(OrderQueryPayload p) {
            return "{\"orderId\":\"" + p.orderId() + "\",\"status\":\"PAID\",\"amount\":9900}";
        }

        @ToolSpec(
                name = "create_refund",
                description = "创建退款",
                riskLevel = RiskLevel.L3_BUSINESS_STATE,
                idempotent = true,
                requiresIdempotencyKey = true,
                maxAmountCents = 500_00)
        public String createRefund(RefundPayload p) {
            return "{\"refundId\":\"RF-001\",\"status\":\"CREATED\",\"amount\":" + p.amountCents() + "}";
        }

        @ToolSpec(
                name = "confirm_refund",
                description = "确认退款完成",
                riskLevel = RiskLevel.L2_REVERSIBLE,
                idempotent = true,
                requiresIdempotencyKey = true)
        public String confirmRefund(ConfirmPayload p) {
            return "{\"refundId\":\"" + p.refundId() + "\",\"status\":\"CONFIRMED\"}";
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        // Phase 17: KbSearchTool 改用 RetrievalPort
        var port = mock(io.github.yysf1949.rag.core.port.RetrievalPort.class);
        when(port.search(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(java.util.List.of(
                        new io.github.yysf1949.rag.core.model.RetrievedChunk(
                                "c-stub", "退款政策：7 天无理由", 0.95,
                                "default", 1L, Map.of())));

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(io.github.yysf1949.rag.core.port.RetrievalPort.class, () -> port);
            ctx.register(KbSearchTool.class, TicketTool.class, InMemoryTicketRepository.class,
                    InMemoryIdempotencyStore.class, ConfirmationService.class, DefaultRiskGate.class,
                    io.github.yysf1949.rag.agent.action.InMemoryToolRegistry.class);
            ctx.refresh();
            registry = ctx.getBean(io.github.yysf1949.rag.agent.action.InMemoryToolRegistry.class);
            registry.scanFromContext(ctx);
        }

        // Register test tools via reflection
        CustomerServiceTools tools = new CustomerServiceTools();
        Field descriptorsField = registry.getClass().getDeclaredField("descriptors");
        descriptorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) descriptorsField.get(registry);
        for (Method m : CustomerServiceTools.class.getMethods()) {
            ToolSpec spec = m.getAnnotation(ToolSpec.class);
            if (spec == null) continue;
            ToolDescriptor desc = new ToolDescriptor(
                    spec.name(), spec.description(), spec.riskLevel(),
                    spec.idempotent(), spec.requiresIdempotencyKey(),
                    spec.maxAmountCents() >= 0 ? spec.maxAmountCents() : null,
                    spec.requiresConfirmationToken(),
                    tools, m);
            map.put(spec.name(), desc);
        }

        // Build AgentLoop with all dependencies
        List<ToolInvocationContext> auditTrail = new ArrayList<>();
        ToolAuditBridge bridge = new ToolAuditBridge(new LlmAuditHook() {
            @Override public void onLlmCall(String t, String u, String s, String q, String m, String pt, String pb, String c, long l, String o) {
                auditTrail.add(new ToolInvocationContext(new AgentIdentity(t, u, s, Set.of()), m, pb, c, l, o));
            }
        });
        IdempotencyStore idem = new InMemoryIdempotencyStore();
        RiskGate gate = new DefaultRiskGate(new ConfirmationService());
        MeterRegistry meterRegistry = new SimpleMeterRegistry();
        AgentMetrics agentMetrics = new AgentMetrics(meterRegistry);
        HumanReviewQueue reviewQueue = new HumanReviewQueue();
        HandoffService handoffService = new HandoffService(reviewQueue, agentMetrics);
        ObjectMapper objectMapper = new ObjectMapper();
        loop = new DefaultAgentLoop(registry, gate, idem, bridge, agentMetrics, handoffService, objectMapper);
    }

    // ── 辅助方法 ──────────────────────────────────────────────

    private static AgentIdentity identity(String userId, String tenantId) {
        return new AgentIdentity(tenantId, userId, "session-" + userId, Set.of("user"));
    }

    // ── 测试用例 ──────────────────────────────────────────────

    /**
     * OFF 模式: debugTrace 为空。
     */
    @Test
    void offMode_debugTraceIsEmpty() {
        loop.setDebugMode(DebugMode.OFF);

        var req = AgentRequest.of(identity("u1", "t1"), "query_order",
                new OrderQueryPayload("ORD-001"), null);
        loop.execute(req);

        assertThat(loop.getDebugTrace()).isEmpty();
        assertThat(loop.getDebugMode()).isEqualTo(DebugMode.OFF);
    }

    /**
     * RECORD 模式: debugTrace 记录了所有步骤。
     */
    @Test
    void recordMode_debugTraceRecordsAllSteps() {
        loop.setDebugMode(DebugMode.RECORD);

        var req = AgentRequest.of(identity("u1", "t1"), "query_order",
                new OrderQueryPayload("ORD-001"), null);
        AgentResponse resp = loop.execute(req);

        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        List<AgentLoopDebugEvent> trace = loop.getDebugTrace();
        // 应有 3 个事件: INTERPRET, SELECT_TOOLS, (VERIFY + EXECUTE)
        // INTERPRET=1, SELECT_TOOLS=2, VERIFY=3, EXECUTE=4
        assertThat(trace).hasSizeGreaterThanOrEqualTo(3);

        // 验证阶段顺序
        assertThat(trace.get(0).phase()).isEqualTo(AgentLoopDebugEvent.Phase.INTERPRET);
        assertThat(trace.get(1).phase()).isEqualTo(AgentLoopDebugEvent.Phase.SELECT_TOOLS);
        // VERIFY 和 EXECUTE 的顺序
        assertThat(trace.get(2).phase()).isIn(
                AgentLoopDebugEvent.Phase.VERIFY, AgentLoopDebugEvent.Phase.EXECUTE);

        // 验证工具名传播
        assertThat(trace).allSatisfy(event ->
                assertThat(event.toolName()).isEqualTo("query_order"));

        // 验证步骤编号递增
        for (int i = 1; i < trace.size(); i++) {
            assertThat(trace.get(i).step()).isGreaterThan(trace.get(i - 1).step());
        }

        // 验证时间戳递增
        for (int i = 1; i < trace.size(); i++) {
            assertThat(trace.get(i).timestamp()).isGreaterThanOrEqualTo(trace.get(i - 1).timestamp());
        }
    }

    /**
     * VERBOSE 模式: 每步都有 stderr 输出。
     */
    @Test
    void verboseMode_printsToStderr() {
        loop.setDebugMode(DebugMode.VERBOSE);

        // 捕获 stderr
        PrintStream originalErr = System.err;
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        System.setErr(new PrintStream(captured));

        try {
            var req = AgentRequest.of(identity("u1", "t1"), "query_order",
                    new OrderQueryPayload("ORD-001"), null);
            loop.execute(req);
        } finally {
            System.setErr(originalErr);
        }

        String stderr = captured.toString();
        // 验证 stderr 包含调试输出
        assertThat(stderr).contains("[DEBUG-TRACE]");
        assertThat(stderr).contains("INTERPRET");
        assertThat(stderr).contains("SELECT_TOOLS");
        assertThat(stderr).contains("query_order");

        // 验证 trace 也有记录
        assertThat(loop.getDebugTrace()).hasSizeGreaterThanOrEqualTo(3);
    }

    /**
     * 完整链路: 查询订单 → 退款 → 确认，追踪包含 4 个阶段。
     */
    @Test
    void fullChain_queryRefundConfirm_traceContainsFourPhases() {
        loop.setDebugMode(DebugMode.RECORD);

        // Step 1: 查询订单（L1_READ）
        var queryReq = AgentRequest.of(identity("u1", "t1"), "query_order",
                new OrderQueryPayload("ORD-001"), null);
        AgentResponse queryResp = loop.execute(queryReq);
        assertThat(queryResp.outcome()).isEqualTo(AgentOutcome.SUCCESS);

        // Step 2: 创建退款（L3_BUSINESS_STATE，需要幂等键）
        var refundKey = IdempotencyKey.of("t1", "u1", "session-u1", "create_refund", "tok-refund-1");
        var refundReq = new AgentRequest(identity("u1", "t1"),
                "create_refund", new RefundPayload("ORD-001", 9900),
                refundKey, AgentChannel.HTTP, false);
        AgentResponse refundResp = loop.execute(refundReq);
        assertThat(refundResp.outcome()).isEqualTo(AgentOutcome.SUCCESS);

        // Step 3: 确认退款（L2_REVERSIBLE，需要幂等键）
        var confirmKey = IdempotencyKey.of("t1", "u1", "session-u1", "confirm_refund", "tok-confirm-1");
        var confirmReq = new AgentRequest(identity("u1", "t1"),
                "confirm_refund", new ConfirmPayload("RF-001"),
                confirmKey, AgentChannel.HTTP, false);
        AgentResponse confirmResp = loop.execute(confirmReq);
        assertThat(confirmResp.outcome()).isEqualTo(AgentOutcome.SUCCESS);

        // 验证完整追踪
        List<AgentLoopDebugEvent> trace = loop.getDebugTrace();
        // 3 次调用，每次至少 3 个事件 (INTERPRET, SELECT_TOOLS, VERIFY, EXECUTE)
        assertThat(trace).hasSizeGreaterThanOrEqualTo(9);

        // 验证所有 4 个阶段都出现
        Set<AgentLoopDebugEvent.Phase> phases = new java.util.HashSet<>();
        for (AgentLoopDebugEvent event : trace) {
            phases.add(event.phase());
        }
        assertThat(phases).containsExactlyInAnyOrder(
                AgentLoopDebugEvent.Phase.INTERPRET,
                AgentLoopDebugEvent.Phase.SELECT_TOOLS,
                AgentLoopDebugEvent.Phase.VERIFY,
                AgentLoopDebugEvent.Phase.EXECUTE);

        // 验证工具名序列
        List<String> toolNames = trace.stream()
                .map(AgentLoopDebugEvent::toolName)
                .distinct()
                .toList();
        assertThat(toolNames).contains("query_order", "create_refund", "confirm_refund");

        // 验证 SELECT_TOOLS 阶段有风险等级
        trace.stream()
                .filter(e -> e.phase() == AgentLoopDebugEvent.Phase.SELECT_TOOLS)
                .forEach(e -> assertThat(e.riskLevel()).isNotNull());

        // 验证 VERIFY 阶段有策略决策
        trace.stream()
                .filter(e -> e.phase() == AgentLoopDebugEvent.Phase.VERIFY)
                .forEach(e -> assertThat(e.policyDecision()).isNotNull());

        // 验证 EXECUTE 阶段有工具结果
        trace.stream()
                .filter(e -> e.phase() == AgentLoopDebugEvent.Phase.EXECUTE)
                .forEach(e -> assertThat(e.toolResult()).isNotEmpty());

        // 验证 INTERPRET 阶段有工具参数
        trace.stream()
                .filter(e -> e.phase() == AgentLoopDebugEvent.Phase.INTERPRET)
                .forEach(e -> assertThat(e.toolArgs()).isNotNull());
    }

    /**
     * 清空调试追踪后重新开始。
     */
    @Test
    void clearDebugTrace_resetsState() {
        loop.setDebugMode(DebugMode.RECORD);

        var req = AgentRequest.of(identity("u1", "t1"), "query_order",
                new OrderQueryPayload("ORD-001"), null);
        loop.execute(req);
        assertThat(loop.getDebugTrace()).isNotEmpty();

        loop.clearDebugTrace();
        assertThat(loop.getDebugTrace()).isEmpty();

        // 再次执行，步骤从 1 重新开始
        loop.execute(req);
        assertThat(loop.getDebugTrace()).isNotEmpty();
        assertThat(loop.getDebugTrace().get(0).step()).isEqualTo(1);
    }

    /**
     * 默认模式是 OFF。
     */
    @Test
    void defaultModeIsOff() {
        assertThat(loop.getDebugMode()).isEqualTo(DebugMode.OFF);
        assertThat(loop.getDebugTrace()).isEmpty();
    }
}
