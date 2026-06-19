package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.builtin.OrderTool;
import io.github.yysf1949.rag.agent.builtin.PaymentChannelTool;
import io.github.yysf1949.rag.agent.builtin.RefundRuleTool;
import io.github.yysf1949.rag.agent.builtin.RefundTool;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryOrderRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryRefundRepository;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.ConfirmationService;
import io.github.yysf1949.rag.agent.governance.ConfirmationToken;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.SensitiveDataMasker;
import io.github.yysf1949.rag.agent.governance.StageAwareToolAuthorizer;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import io.github.yysf1949.rag.agent.handoff.HumanReviewQueue;
import io.github.yysf1949.rag.agent.service.OrderApplicationService;
import io.github.yysf1949.rag.agent.service.RefundApplicationService;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentLoop 集成测试 — 验证完整请求链路从进入到退出。
 *
 * <p>核心验证：不是单个工具能工作就行，而是整个请求链路
 * AgentLoop → RiskGate → Tool → ApplicationService → Repository 都要可验证。</p>
 *
 * <h2>约束</h2>
 * <ul>
 *   <li>不启动 Spring Context — 所有 Bean 手工 new（构造函数注入）</li>
 *   <li>使用 InMemory 存储（不依赖 H2/Redis）</li>
 *   <li>使用 SimpleMeterRegistry 验证 metrics 埋点</li>
 * </ul>
 */
class AgentLoopIntegrationTest {

    // ── Repositories ─────────────────────────────────────────────
    private InMemoryOrderRepository orderRepo;
    private InMemoryRefundRepository refundRepo;

    // ── Core services ────────────────────────────────────────────
    private InMemoryIdempotencyStore idemStore;
    private SimpleMeterRegistry meterRegistry;
    private AgentMetrics metrics;
    private ConfirmationService confirmationService;

    // ── Application services ─────────────────────────────────────
    private OrderApplicationService orderAppService;
    private RefundApplicationService refundAppService;

    // ── Tools ────────────────────────────────────────────────────
    private OrderTool orderTool;
    private RefundTool refundTool;

    // ── Governance ───────────────────────────────────────────────
    private DefaultRiskGate riskGate;
    private ToolAuditBridge auditBridge;
    private HandoffService handoffService;
    private InMemoryToolRegistry registry;
    private StageAwareToolAuthorizer authorizer;

    // ── AgentLoop ────────────────────────────────────────────────
    private DefaultAgentLoop loop;

    // ── Audit trail ──────────────────────────────────────────────
    private List<ToolInvocationContext> auditTrail;

    @BeforeEach
    void setUp() throws Exception {
        // 1. Repositories (InMemory, no DB)
        orderRepo = new InMemoryOrderRepository();
        refundRepo = new InMemoryRefundRepository();

        // 2. Core services
        idemStore = new InMemoryIdempotencyStore();
        meterRegistry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(meterRegistry);
        confirmationService = new ConfirmationService();

        // 3. Tool dependency chain
        PaymentChannelTool paymentChannelTool = new PaymentChannelTool();
        RefundRuleTool refundRuleTool = new RefundRuleTool(paymentChannelTool);

        // 4. Application services (Agent 和管理后台共用)
        orderAppService = new OrderApplicationService(orderRepo, idemStore, metrics);
        refundAppService = new RefundApplicationService(refundRepo, refundRuleTool, idemStore, metrics);

        // 5. Tool beans
        orderTool = new OrderTool(orderAppService);
        refundTool = new RefundTool(refundAppService);

        // 6. Risk gate (with ConfirmationService for token validation)
        riskGate = new DefaultRiskGate(confirmationService);

        // 7. Audit bridge (NOOP hook, capture to list)
        auditTrail = new ArrayList<>();
        auditBridge = new ToolAuditBridge(new LlmAuditHook() {
            @Override
            public void onLlmCall(String tenantId, String userId, String sessionId,
                                  String queryHash, String modelId, String promptTemplate,
                                  String promptBody, String completion, long latencyMs, String outcome) {
                auditTrail.add(new ToolInvocationContext(
                        new AgentIdentity(tenantId, userId, sessionId, Set.of()),
                        modelId, promptBody, completion, latencyMs, outcome));
            }
        }, new SensitiveDataMasker(), metrics);

        // 8. Handoff service
        handoffService = new HandoffService(new HumanReviewQueue(), metrics);

        // 9. Tool registry — register tools manually (no Spring context)
        registry = new InMemoryToolRegistry();
        registerTool("get_order", "查询订单详情",
                RiskLevel.L1_READ, true, false, null, false,
                orderTool,
                OrderTool.class.getMethod("getOrder", OrderTool.GetOrderRequest.class));

        registerTool("list_orders", "查询用户订单列表",
                RiskLevel.L1_READ, true, false, null, false,
                orderTool,
                OrderTool.class.getMethod("listOrders", OrderTool.ListOrdersRequest.class));

        registerTool("cancel_order", "取消订单",
                RiskLevel.L3_BUSINESS_STATE, true, true, 100_00L, true,
                orderTool,
                OrderTool.class.getMethod("cancelOrder", IdempotencyKey.class, OrderTool.CancelOrderRequest.class));

        registerTool("create_refund", "创建退款申请",
                RiskLevel.L3_BUSINESS_STATE, true, true, 500_00L, true,
                refundTool,
                RefundTool.class.getMethod("createRefund", RefundTool.CreateRefundRequest.class));

        // 10. AgentLoop (手工 new, 无 Spring)
        loop = new DefaultAgentLoop(registry, riskGate, idemStore, auditBridge,
                metrics, handoffService, new ObjectMapper());

        // 11. StageAwareToolAuthorizer (for tool filtering test)
        authorizer = new StageAwareToolAuthorizer(registry);

        // 12. Seed test data
        seedTestData();
    }

    /**
     * 反射注册工具到 InMemoryToolRegistry — 绕过 Spring context 扫描。
     */
    private void registerTool(String name, String description, RiskLevel riskLevel,
                              boolean idempotent, boolean requiresIdempotencyKey,
                              Long maxAmountCents, boolean requiresConfirmationToken,
                              Object bean, Method method) throws Exception {
        Field descriptorsField = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        descriptorsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) descriptorsField.get(registry);
        map.put(name, new ToolDescriptor(
                name, description, riskLevel, idempotent, requiresIdempotencyKey,
                maxAmountCents, requiresConfirmationToken, bean, method));
    }

    private void seedTestData() {
        // 订单 ORD-001: PAID 状态, 50 元
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-001", "T001", "U001", 5000L, "PAID"));
        // 订单 ORD-002: CREATED 状态, 30 元
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-002", "T001", "U001", 3000L, "CREATED"));
    }

    // ════════════════════════════════════════════════════════════════
    //  测试场景 1: 查询订单 — 完整链路 AgentLoop → Tool → ApplicationService → Repository
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("场景1: 查询订单 ORD-001 → 链路 AgentLoop→OrderTool→OrderApplicationService→InMemoryRepo")
    void queryOrder_fullChain_returnsOrderInfo() {
        // Arrange — 模拟 LLM 返回 tool_call: getOrder("ORD-001", "T001")
        var identity = new AgentIdentity("T001", "U001", "S001", Set.of("user"));
        var payload = new OrderTool.GetOrderRequest("T001", "U001", "ORD-001");
        var request = AgentRequest.of(identity, "get_order", payload, null);

        // Act — AgentLoop 执行工具
        AgentResponse response = loop.execute(request);

        // Assert: outcome = SUCCESS
        assertThat(response.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        assertThat(response.toolName()).isEqualTo("get_order");

        // Assert: toolResponse 包含订单信息
        assertThat(response.toolResponse()).isInstanceOf(OrderTool.GetOrderResponse.class);
        var orderResp = (OrderTool.GetOrderResponse) response.toolResponse();
        assertThat(orderResp.orderId()).isEqualTo("ORD-001");
        assertThat(orderResp.status()).isEqualTo("PAID");
        assertThat(orderResp.amountCents()).isEqualTo(5000L);
        assertThat(orderResp.userId()).isEqualTo("U001");

        // Assert: message (JSON) 也包含关键信息
        assertThat(response.message()).contains("ORD-001");
        assertThat(response.message()).contains("PAID");

        // Assert: AgentMetrics 记录了 1 次 tool invocation
        double invocations = meterRegistry.counter("agent.tool.invocations",
                "tool", "get_order", "outcome", "SUCCESS").count();
        assertThat(invocations).isEqualTo(1.0);

        // Assert: 审计记录
        assertThat(auditTrail).hasSize(1);
        assertThat(auditTrail.get(0).outcome()).isEqualTo("SUCCESS");
        assertThat(auditTrail.get(0).toolName()).contains("get_order");
    }

    // ════════════════════════════════════════════════════════════════
    //  测试场景 2: 退款 — 带确认令牌, 链路到 Repository 持久化
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("场景2: 退款 ORD-001 → 带确认令牌 → 退款记录存在于 InMemoryRefundRepository")
    void createRefund_withConfirmationToken_succeedsAndPersists() {
        // Arrange — 模拟 LLM 返回 tool_call: createRefund(orderId="ORD-001", amountCents=5000, ...)
        // Step 1: 生成确认令牌（模拟用户已确认）
        ConfirmationToken token = confirmationService.generate("create_refund", "U001");

        // Step 2: 创建带确认令牌的 identity
        var identity = new AgentIdentity("T001", "U001", "S001", Set.of("user"))
                .withConfirmationToken(token.rawToken());

        // Step 3: 创建请求（带幂等键 + 确认令牌）
        var idemKey = IdempotencyKey.of("T001", "U001", "S001", "create_refund", "KEY-001");
        var payload = new RefundTool.CreateRefundRequest(
                "T001", "U001", "ORD-001", 5000L, "质量问题");
        var request = new AgentRequest(identity, "create_refund", payload,
                idemKey, AgentChannel.HTTP, false);

        // Act
        AgentResponse response = loop.execute(request);

        // Assert: outcome = SUCCESS
        assertThat(response.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        assertThat(response.toolName()).isEqualTo("create_refund");

        // Assert: toolResponse 包含退款信息
        assertThat(response.toolResponse()).isInstanceOf(RefundTool.CreateRefundResponse.class);
        var refundResp = (RefundTool.CreateRefundResponse) response.toolResponse();
        assertThat(refundResp.status()).isEqualTo("PENDING");
        assertThat(refundResp.amountCents()).isEqualTo(5000L);
        assertThat(refundResp.refundId()).isNotBlank();

        // Assert: 退款记录存在于 InMemoryRefundRepository（链路穿透到存储层）
        var savedRefund = refundRepo.findByIdAndTenant(refundResp.refundId(), "T001");
        assertThat(savedRefund).isPresent();
        assertThat(savedRefund.get().orderId()).isEqualTo("ORD-001");
        assertThat(savedRefund.get().amountCents()).isEqualTo(5000L);
        assertThat(savedRefund.get().reason()).isEqualTo("质量问题");
        assertThat(savedRefund.get().status()).isEqualTo("PENDING");

        // Assert: AgentMetrics 记录了 invocations + audit
        double invocations = meterRegistry.counter("agent.tool.invocations",
                "tool", "create_refund", "outcome", "SUCCESS").count();
        assertThat(invocations).isEqualTo(1.0);

        // Assert: 审计记录
        assertThat(auditTrail).hasSize(1);
        assertThat(auditTrail.get(0).outcome()).isEqualTo("SUCCESS");
    }

    // ════════════════════════════════════════════════════════════════
    //  测试场景 3: 工具过滤 — 早期轮次只允许 L1 工具
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("场景3: 早期轮次(conversationTurn=1) → StageAwareToolAuthorizer 拦截 L3 工具")
    void toolFiltering_awaitingConfirmation_onlyL1ToolsVisible() {
        // Arrange — 模拟早期轮次，用户尚未确认
        var identity = new AgentIdentity("T001", "U001", "S001", Set.of("user"));
        AuthorizationContext awaitingCtx = AuthorizationContext.awaitingConfirmation(identity);
        List<String> allTools = registry.listNames();

        // Act — StageAwareToolAuthorizer 过滤工具
        List<String> authorized = authorizer.authorizedTools(awaitingCtx, allTools);

        // Assert: L1 工具可见
        assertThat(authorized).contains("get_order", "list_orders");

        // Assert: L3 工具被拦截（create_refund, cancel_order 不可见）
        assertThat(authorized).doesNotContain("create_refund");
        assertThat(authorized).doesNotContain("cancel_order");

        // Act — 对比已确认阶段：所有工具可见
        AuthorizationContext confirmedCtx = AuthorizationContext.confirmed(identity);
        List<String> allAuthorized = authorizer.authorizedTools(confirmedCtx, allTools);

        // Assert: 已确认阶段 L1-L3 全部可见
        assertThat(allAuthorized).contains("get_order", "list_orders", "cancel_order", "create_refund");
    }

    // ════════════════════════════════════════════════════════════════
    //  测试场景 4: 确认令牌验证 — 缺少确认令牌应被拒绝
    // ════════════════════════════════════════════════════════════════

    @Test
    @DisplayName("场景4: cancel_order 缺少确认令牌 → RiskGate 拒绝 → 返回 DENIED")
    void cancelOrder_withoutConfirmationToken_returnsDenied() {
        // Arrange — 模拟 LLM 返回 tool_call: cancelOrder(orderId, reason) 没有 confirmationToken
        var identity = new AgentIdentity("T001", "U001", "S001", Set.of("user"));
        // 注意：identity 没有 confirmationToken
        var idemKey = IdempotencyKey.of("T001", "U001", "S001", "cancel_order", "KEY-CANCEL-001");
        var payload = new OrderTool.CancelOrderRequest(
                "T001", "U001", "ORD-002", 3000L, "不想要了");
        var request = new AgentRequest(identity, "cancel_order", payload,
                idemKey, AgentChannel.HTTP, false);

        // Act
        AgentResponse response = loop.execute(request);

        // Assert: 被拒绝
        assertThat(response.outcome()).isEqualTo(AgentOutcome.DENIED);
        assertThat(response.toolName()).isEqualTo("cancel_order");
        assertThat(response.message()).contains("confirmationToken");

        // Assert: AgentMetrics 记录了 DENIED
        double deniedCount = meterRegistry.counter("agent.tool.invocations",
                "tool", "cancel_order", "outcome", "DENIED").count();
        assertThat(deniedCount).isEqualTo(1.0);

        // Assert: 订单状态未变（仍为 CREATED，未被取消）
        var order = orderRepo.findByIdAndTenant("ORD-002", "T001");
        assertThat(order).isPresent();
        assertThat(order.get().status()).isEqualTo("CREATED");
    }
}
