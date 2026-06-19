package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.builtin.OrderTool;
import io.github.yysf1949.rag.agent.builtin.SatisfactionSurveyTool;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryOrderRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemorySatisfactionSurveyRepository;
import io.github.yysf1949.rag.agent.service.OrderApplicationService;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 可观测性端到端验证测试 — 确保所有 Micrometer 指标在工具执行时正确记录。
 *
 * <h2>对齐「路条编程」文章</h2>
 * <p>「没有可观测性，就不可能放心让 Agent 处理真实业务」</p>
 *
 * <p>本测试模拟完整的工具执行管线：工具调用 → 治理层指标记录 → 审计日志，
 * 验证 Micrometer 指标在各种场景下正确记录。</p>
 *
 * <h2>测试场景</h2>
 * <ol>
 *   <li>工具调用指标 — 成功执行后 invocations/latency 记录</li>
 *   <li>业务错误指标 — 无效状态取消订单时 business_errors 记录</li>
 *   <li>审计指标 — 每次工具调用后 audit.total 记录</li>
 *   <li>确认指标 — 用户拒绝确认时 confirmations 记录</li>
 *   <li>回滚指标 — 工具回滚时 rollbacks 记录</li>
 *   <li>转人工质量指标 — 转人工时 handoff_quality 记录</li>
 *   <li>多工具链路指标 — 链式调用多个工具后汇总验证</li>
 * </ol>
 */
class ObservabilityE2ETest {

    private SimpleMeterRegistry registry;
    private AgentMetrics metrics;
    private AuditLogger auditLogger;
    private ToolAuditBridge auditBridge;
    private InMemoryOrderRepository orderRepo;
    private InMemoryIdempotencyStore idemStore;
    private OrderApplicationService orderService;
    private OrderTool orderTool;
    private SatisfactionSurveyTool surveyTool;

    private static final String TENANT = "tenant-1";
    private static final String USER = "user-1";
    private static final String SESSION = "session-1";

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
        auditLogger = new AuditLogger(registry);
        auditBridge = new ToolAuditBridge(LlmAuditHook.NOOP, new SensitiveDataMasker(), metrics);
        orderRepo = new InMemoryOrderRepository();
        idemStore = new InMemoryIdempotencyStore();
        orderService = new OrderApplicationService(orderRepo, idemStore, metrics);
        orderTool = new OrderTool(orderService);
        surveyTool = new SatisfactionSurveyTool(
                new InMemorySatisfactionSurveyRepository(), idemStore);

        // 预置订单数据
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-1", TENANT, USER, 50_00L, "CREATED"));
        orderRepo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-2", TENANT, USER, 50_00L, "SHIPPED"));
    }

    // ====== 1. 工具调用指标 — 成功执行 getOrder ======

    @Test
    void toolInvocationMetrics_getOrder_success() {
        // 执行工具
        var resp = orderTool.getOrder(new OrderTool.GetOrderRequest(TENANT, USER, "ORD-1"));
        assertThat(resp.orderId()).isEqualTo("ORD-1");

        // 模拟治理层记录指标
        metrics.recordToolInvocation("get_order", AgentOutcome.SUCCESS, 42);
        auditLogger.logToolInvocation("get_order", "L1_READ",
                "{\"orderId\":\"ORD-1\"}", "SUCCESS", 42, null);

        // 验证 invocations 计数
        Counter invocations = registry.counter("agent.tool.invocations",
                "tool", "get_order", "outcome", "SUCCESS");
        assertThat(invocations.count()).isEqualTo(1.0);

        // 验证 latency 有记录
        Timer latency = registry.find("agent.tool.latency")
                .tag("tool", "get_order").timer();
        assertThat(latency).isNotNull();
        assertThat(latency.count()).isEqualTo(1);
        assertThat(latency.totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isGreaterThan(0);

        // 验证 errors 计数器不变（成功不应增加）
        double errorCount = registry.counter("agent.tool.errors").count();
        assertThat(errorCount).isEqualTo(0.0);
    }

    // ====== 2. 业务错误指标 — cancelOrder 无效状态 ======

    @Test
    void businessErrorMetrics_cancelOrder_invalidStatus() {
        // ORD-2 是 SHIPPED 状态，不可取消 → 触发 INVALID_STATUS 业务错误
        IdempotencyKey key = IdempotencyKey.of(TENANT, USER, SESSION, "cancel_order", "tk-err-1");

        assertThatThrownBy(() ->
                orderTool.cancelOrder(key, new OrderTool.CancelOrderRequest(
                        TENANT, USER, "ORD-2", 50_00L, "尝试取消已发货订单")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPPED");

        // OrderApplicationService 内部已调用 agentMetrics.recordBusinessError
        Counter bizErrors = registry.counter("agent.tool.business_errors",
                "tool", "cancel_order", "errorType", "INVALID_STATUS");
        assertThat(bizErrors.count()).isEqualTo(1.0);

        // 模拟治理层记录 FAILURE outcome
        metrics.recordToolInvocation("cancel_order", AgentOutcome.FAILURE, 10);

        Counter failureInvocations = registry.counter("agent.tool.invocations",
                "tool", "cancel_order", "outcome", "FAILURE");
        assertThat(failureInvocations.count()).isEqualTo(1.0);
    }

    // ====== 3. 审计指标 — audit.total ======

    @Test
    void auditMetrics_toolInvocation() {
        // 执行工具 + 记录审计
        orderTool.getOrder(new OrderTool.GetOrderRequest(TENANT, USER, "ORD-1"));
        auditLogger.logToolInvocation("get_order", "L1_READ",
                "{\"orderId\":\"ORD-1\"}", "SUCCESS", 35, null);

        Counter auditTotal = registry.counter("agent.audit.total",
                "tool", "get_order", "outcome", "SUCCESS");
        assertThat(auditTotal.count()).isEqualTo(1.0);
    }

    // ====== 4. 确认指标 — 确认被拒绝 ======

    @Test
    void confirmationMetrics_rejected() {
        // 模拟确认被拒绝
        auditBridge.recordConfirmationRejected("cancel_order");

        Counter confirmations = registry.counter("agent.tool.confirmations",
                "tool", "cancel_order", "confirmed", "false");
        assertThat(confirmations.count()).isEqualTo(1.0);
    }

    // ====== 5. 回滚指标 ======

    @Test
    void rollbackMetrics_recorded() {
        // 模拟回滚
        metrics.recordRollback("cancel_order", "user_cancelled");

        Counter rollbacks = registry.counter("agent.tool.rollbacks",
                "tool", "cancel_order", "reason", "user_cancelled");
        assertThat(rollbacks.count()).isEqualTo(1.0);
    }

    // ====== 6. 转人工质量指标 ======

    @Test
    void handoffQualityMetrics_withContext() {
        // 模拟有上下文的转人工
        metrics.recordHandoffQuality(true, "HANDOFF_REQUIRED");

        Counter handoffQuality = registry.counter("agent.conversation.handoff_quality",
                "hasContext", "true", "handoffReason", "HANDOFF_REQUIRED");
        assertThat(handoffQuality.count()).isEqualTo(1.0);
    }

    @Test
    void handoffQualityMetrics_viaAuditBridge() {
        // 通过 ToolAuditBridge 模拟 HANDOFF_REQUIRED outcome
        AgentIdentity identity = new AgentIdentity(TENANT, USER, SESSION, Set.of());
        ToolInvocationContext ctx = new ToolInvocationContext(
                identity, "cancel_order",
                "{\"orderId\":\"ORD-2\"}", null,
                100, "HANDOFF_REQUIRED");
        auditBridge.record(ctx);

        Counter handoffQuality = registry.counter("agent.conversation.handoff_quality",
                "hasContext", "true", "handoffReason", "HANDOFF_REQUIRED");
        assertThat(handoffQuality.count()).isEqualTo(1.0);
    }

    // ====== 7. 多工具链路指标 ======

    @Test
    void multiToolChainMetrics_getOrder_cancelOrder_submitSurvey() {
        // Step 1: getOrder
        var orderResp = orderTool.getOrder(
                new OrderTool.GetOrderRequest(TENANT, USER, "ORD-1"));
        assertThat(orderResp.orderId()).isEqualTo("ORD-1");
        metrics.recordToolInvocation("get_order", AgentOutcome.SUCCESS, 30);
        auditLogger.logToolInvocation("get_order", "L1_READ",
                "{\"orderId\":\"ORD-1\"}", "SUCCESS", 30, null);

        // Step 2: cancelOrder (CREATED 状态，可取消)
        IdempotencyKey cancelKey = IdempotencyKey.of(TENANT, USER, SESSION, "cancel_order", "tk-chain-1");
        var cancelResp = orderTool.cancelOrder(cancelKey,
                new OrderTool.CancelOrderRequest(TENANT, USER, "ORD-1", 50_00L, "用户取消"));
        assertThat(cancelResp.status()).isEqualTo("CANCELLED");
        metrics.recordToolInvocation("cancel_order", AgentOutcome.SUCCESS, 85);
        auditLogger.logToolInvocation("cancel_order", "L3_BUSINESS_STATE",
                "{\"orderId\":\"ORD-1\"}", "SUCCESS", 85, null);

        // Step 3: submitSurvey
        IdempotencyKey surveyKey = IdempotencyKey.of(TENANT, USER, SESSION, "submit_satisfaction_survey", "tk-chain-2");
        var surveyResp = surveyTool.submitSurvey(surveyKey,
                new SatisfactionSurveyTool.SurveyRequest(
                        TENANT, USER, "conv-1", 4, "很好", true));
        assertThat(surveyResp.surveyId()).isNotNull();
        metrics.recordToolInvocation("submit_satisfaction_survey", AgentOutcome.SUCCESS, 20);
        auditLogger.logToolInvocation("submit_satisfaction_survey", "L2_REVERSIBLE",
                "{\"rating\":4}", "SUCCESS", 20, null);

        // ====== 验证汇总 ======

        // invocations 总数 = 3（每个工具各 1 次 SUCCESS）
        double totalInvocations = registry.counter("agent.tool.invocations",
                "tool", "get_order", "outcome", "SUCCESS").count()
                + registry.counter("agent.tool.invocations",
                "tool", "cancel_order", "outcome", "SUCCESS").count()
                + registry.counter("agent.tool.invocations",
                "tool", "submit_satisfaction_survey", "outcome", "SUCCESS").count();
        assertThat(totalInvocations).isEqualTo(3.0);

        // audit.total 总数 = 3
        double totalAudit = registry.counter("agent.audit.total",
                "tool", "get_order", "outcome", "SUCCESS").count()
                + registry.counter("agent.audit.total",
                "tool", "cancel_order", "outcome", "SUCCESS").count()
                + registry.counter("agent.audit.total",
                "tool", "submit_satisfaction_survey", "outcome", "SUCCESS").count();
        assertThat(totalAudit).isEqualTo(3.0);

        // 每个工具的 latency 都有记录
        assertThat(registry.find("agent.tool.latency").tag("tool", "get_order").timer())
                .isNotNull();
        assertThat(registry.find("agent.tool.latency").tag("tool", "cancel_order").timer())
                .isNotNull();
        assertThat(registry.find("agent.tool.latency")
                .tag("tool", "submit_satisfaction_survey").timer())
                .isNotNull();
    }
}
