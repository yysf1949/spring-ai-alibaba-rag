package io.github.yysf1949.rag.agent.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.*;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.builtin.*;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryCouponRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryOrderRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryRefundRepository;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryTicketRepository;
import io.github.yysf1949.rag.agent.governance.*;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import io.github.yysf1949.rag.agent.handoff.HumanReviewQueue;
import io.github.yysf1949.rag.agent.orchestration.DefaultAgentLoop;
import io.github.yysf1949.rag.agent.service.CouponApplicationService;
import io.github.yysf1949.rag.agent.service.OrderApplicationService;
import io.github.yysf1949.rag.agent.service.RefundApplicationService;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.github.yysf1949.rag.core.port.QAService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 10 端到端测试 — 验证 P0+P1 全部 ship 后能跑通完整业务流。
 *
 * <p>覆盖 6 场景：
 * <ol>
 *   <li>L1 kb_search 读</li>
 *   <li>L1 query_logistics 读</li>
 *   <li>L3 cancel_order &lt; 100 元 (通过)</li>
 *   <li>L3 cancel_order &gt; 100 元 (转人工)</li>
 *   <li>L4 approve_refund user 角色 (转人工)</li>
 *   <li>L4 approve_refund admin 角色 (通过)</li>
 * </ol>
 */
class Phase10EndToEndTest {
    private AgentIdentity withConfirmationToken(AgentIdentity id, String toolName) {
        var token = confirmationService.generate(toolName, id.userId());
        return id.withConfirmationToken(token.rawToken());
    }


    private DefaultAgentLoop loop;
    private ConfirmationService confirmationService;
    private InMemoryOrderRepository orderRepo;
    private InMemoryRefundRepository refundRepo;

    @BeforeEach
    void setUp() {
        // Phase 17: KbSearchTool 改用 RetrievalPort (不再依赖 QAService 8 步链)
        var port = mock(io.github.yysf1949.rag.core.port.RetrievalPort.class);
        when(port.search(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(java.util.List.of(
                        new io.github.yysf1949.rag.core.model.RetrievedChunk(
                                "c-stub", "退款政策 7 天无理由", 0.95,
                                "default", 1L, java.util.Map.of())));

        var auditOutcomes = new ArrayList<String>();

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(io.github.yysf1949.rag.core.port.RetrievalPort.class, () -> port);
            ctx.registerBean(MeterRegistry.class, SimpleMeterRegistry::new);
            ctx.register(KbSearchTool.class, TicketTool.class, InMemoryTicketRepository.class,
                    InMemoryIdempotencyStore.class, ConfirmationService.class, DefaultRiskGate.class,
                    InMemoryToolRegistry.class,
                    OrderTool.class, InMemoryOrderRepository.class,
                    RefundTool.class, InMemoryRefundRepository.class,
                    CouponTool.class, InMemoryCouponRepository.class,
                    LogisticsTool.class,
                    // Phase 13b M5: RefundTool 现在依赖 PaymentChannelTool + RefundRuleTool
                    PaymentChannelTool.class, RefundRuleTool.class,
                    // Domain services — Agent 和管理后台共用
                    AgentMetrics.class,
                    OrderApplicationService.class,
                    RefundApplicationService.class,
                    CouponApplicationService.class);
            ctx.refresh();

            ToolRegistry registry = ctx.getBean(InMemoryToolRegistry.class);
            registry.scanFromContext(ctx);
            orderRepo = ctx.getBean(InMemoryOrderRepository.class);
            refundRepo = ctx.getBean(InMemoryRefundRepository.class);

            // 预置一个可取消的订单
            orderRepo.save(new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "user-1", 50_00L, "CREATED"));

            LlmAuditHook hook = (t, u, s, q, m, pt, pb, c, l, o) -> auditOutcomes.add(o);
            ToolAuditBridge bridge = new ToolAuditBridge(hook);
            IdempotencyStore idem = ctx.getBean(InMemoryIdempotencyStore.class);
            confirmationService = new ConfirmationService();
            RiskGate gate = new DefaultRiskGate(confirmationService);
            AgentMetrics metrics = ctx.getBean(AgentMetrics.class);
            HandoffService handoffService = new HandoffService(new HumanReviewQueue(), metrics);
            ObjectMapper objectMapper = new ObjectMapper();
            loop = new DefaultAgentLoop(registry, gate, idem, bridge,
                    metrics, handoffService, objectMapper);
        }
    }

    private AgentIdentity identity(String tenantId, String userId, Set<String> roles) {
        return new AgentIdentity(tenantId, userId, "session-" + userId, roles);
    }

    private AgentIdentity identity(String tenantId, String userId, String sessionId, Set<String> roles) {
        return new AgentIdentity(tenantId, userId, sessionId, roles);
    }

    @Test
    void l1KbSearchHappyPath() {
        var req = AgentRequest.of(identity("t1", "user-1", Set.of("user")), "kb_search",
                new KbSearchRequest("t1", "default", -1L, "怎么退款",
                        5, List.of()), null);
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }

    @Test
    void l1LogisticsHappyPath() {
        var req = AgentRequest.of(identity("t1", "user-1", Set.of("user")), "query_logistics",
                new LogisticsTool.QueryRequest("t1", "user-1", "ORD-1"), null);
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }

    @Test
    void l3CancelOrderUnderLimit() {
        var req = AgentRequest.of(withConfirmationToken(identity("t1", "user-1", Set.of("user")), "cancel_order"), "cancel_order",
                new OrderTool.CancelOrderRequest("t1", "user-1", "ORD-1", 50_00L, "ok"),
                IdempotencyKey.of("t1", "user-1", "s1", "cancel_order", "cancel-1"));
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }

    @Test
    void l3CancelOrderOverLimitTriggersHandoff() {
        var req = AgentRequest.of(withConfirmationToken(identity("t1", "user-1", Set.of("user")), "cancel_order"), "cancel_order",
                new OrderTool.CancelOrderRequest("t1", "user-1", "ORD-1", 500_00L, "高额"),
                IdempotencyKey.of("t1", "user-1", "s1", "cancel_order", "cancel-big-1"));
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext()).isNotNull();
        assertThat(resp.handoffContext().reason()).isEqualTo("AMOUNT_LIMIT_EXCEEDED");
    }

    @Test
    void l4ApproveRefundWithoutAdminTriggersHandoff() {
        // 先创建退款
        var createReq = AgentRequest.of(withConfirmationToken(identity("t1", "user-1", Set.of("user")), "create_refund"), "create_refund",
                new RefundTool.CreateRefundRequest("t1", "user-1", "ORD-1", 50_00L, "ok"),
                IdempotencyKey.of("t1", "user-1", "s1", "create_refund", "refund-create-1"));
        var created = loop.execute(createReq);
        assertThat(created.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        var createResp = (RefundTool.CreateRefundResponse) created.toolResponse();
        String refundId = createResp.refundId();

        // 用普通 user 角色调 L4 approve → 期望 HANDOFF_REQUIRED
        var approveReq = AgentRequest.of(
                identity("t1", "user-1", "s1", Set.of("user")),
                "approve_refund",
                new RefundTool.ApproveRefundRequest("t1", "user-1", refundId, 50_00L),
                IdempotencyKey.of("t1", "user-1", "s1", "approve_refund", "refund-approve-1"));
        AgentResponse resp = loop.execute(approveReq);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.HANDOFF_REQUIRED);
        assertThat(resp.handoffContext().reason()).isEqualTo("INSUFFICIENT_PRIVILEGE");
    }

    @Test
    void l4ApproveRefundWithAdminSucceeds() {
        var createReq = AgentRequest.of(withConfirmationToken(identity("t1", "admin-1", Set.of("admin")), "create_refund"), "create_refund",
                new RefundTool.CreateRefundRequest("t1", "admin-1", "ORD-1", 50_00L, "ok"),
                IdempotencyKey.of("t1", "admin-1", "s1", "create_refund", "refund-create-2"));
        var created = loop.execute(createReq);
        assertThat(created.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        var createResp2 = (RefundTool.CreateRefundResponse) created.toolResponse();
        String refundId2 = createResp2.refundId();

        var approveReq = AgentRequest.of(
                identity("t1", "admin-1", "s1", Set.of("admin")),
                "approve_refund",
                new RefundTool.ApproveRefundRequest("t1", "admin-1", refundId2, 50_00L),
                IdempotencyKey.of("t1", "admin-1", "s1", "approve_refund", "refund-approve-2"));
        AgentResponse resp = loop.execute(approveReq);
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
    }
}
