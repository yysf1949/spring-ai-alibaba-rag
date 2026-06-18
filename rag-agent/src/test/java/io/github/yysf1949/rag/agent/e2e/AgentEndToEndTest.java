package io.github.yysf1949.rag.agent.e2e;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.TicketTool;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryTicketRepository;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import io.github.yysf1949.rag.agent.handoff.HumanReviewQueue;
import io.github.yysf1949.rag.agent.orchestration.AgentLoop;
import io.github.yysf1949.rag.agent.orchestration.DefaultAgentLoop;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.github.yysf1949.rag.core.port.QAService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 9 端到端冒烟测试 — 不接真实 LLM，走 stub QAService。
 *
 * <h2>覆盖</h2>
 * <ul>
 *   <li>L1 工具（kb_search）成功路径 + 审计</li>
 *   <li>L2 工具（create_reminder_ticket）首次创建 + 同 key 幂等</li>
 *   <li>风险门控：L2 缺 idempotencyKey 拒绝 + 审计 DENIED</li>
 *   <li>租户隔离：tenant2 不能看到 tenant1 的工单</li>
 * </ul>
 */
class AgentEndToEndTest {

    private AgentLoop agentService;
    private InMemoryTicketRepository ticketRepo;
    private List<String> auditOutcomes;

    @BeforeEach
    void setUp() {
        QAService qa = mock(QAService.class);
        when(qa.answer(any(Query.class))).thenReturn(new Answer(
                "t1", "qh-stub", "怎么退款",
                List.of(), List.of(),
                "退款政策 7 天无理由", List.of(),
                AnswerSource.LLM, 0L, java.util.Map.of()));

        try (var ctx = new AnnotationConfigApplicationContext()) {
            ctx.registerBean(QAService.class, () -> qa);
            ctx.register(KbSearchTool.class, TicketTool.class, InMemoryTicketRepository.class,
                    InMemoryIdempotencyStore.class, DefaultRiskGate.class,
                    InMemoryToolRegistry.class);
            ctx.refresh();
            ToolRegistry registry = ctx.getBean(InMemoryToolRegistry.class);
            registry.scanFromContext(ctx);
            ticketRepo = ctx.getBean(InMemoryTicketRepository.class);

            auditOutcomes = new ArrayList<>();
            LlmAuditHook hook = (t, u, s, q, m, pt, pb, c, l, o) -> auditOutcomes.add(o);
            ToolAuditBridge bridge = new ToolAuditBridge(hook);
            IdempotencyStore idem = ctx.getBean(InMemoryIdempotencyStore.class);
            RiskGate gate = new DefaultRiskGate();
            AgentMetrics metrics = new AgentMetrics(new SimpleMeterRegistry());
            HandoffService handoffService = new HandoffService(new HumanReviewQueue(), metrics);
            ObjectMapper objectMapper = new ObjectMapper();
            agentService = new DefaultAgentLoop(registry, gate, idem, bridge,
                    metrics, handoffService, objectMapper);
        }
    }

    @Test
    void l1KbSearchHappyPath() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        AgentResponse resp = agentService.execute(AgentRequest.of(identity, "kb_search",
                new KbSearchTool.Request("t1", "u1", "怎么退款", Set.of(), 5, null), null));
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        assertThat(auditOutcomes).contains("SUCCESS");
    }

    @Test
    void l2CreateTicketHappyPath() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "tok-E2E-1");
        AgentResponse resp = agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "kb 返回空结果，请人工"), key));
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.SUCCESS);
        TicketTool.Response r = (TicketTool.Response) resp.toolResponse();
        assertThat(r.ticketId()).startsWith("TKT-");
        assertThat(r.status()).isEqualTo("PENDING");
        assertThat(ticketRepo.findByTenant("t1")).hasSize(1);
    }

    @Test
    void l2IdempotencyReplay() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "tok-E2E-2");
        var r1 = agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "first"), key));
        var r2 = agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "second"), key));
        assertThat(r1.toolResponse()).isEqualTo(r2.toolResponse());
        assertThat(ticketRepo.findByTenant("t1")).hasSize(1);
    }

    @Test
    void l2MissingIdempotencyKeyDenied() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        // Phase 10: execute() 返回 DENIED 而非抛异常
        AgentResponse resp = agentService.execute(AgentRequest.of(identity, "create_reminder_ticket",
                new TicketTool.Request("kb-search", "no key"), null));
        assertThat(resp.outcome()).isEqualTo(AgentOutcome.DENIED);
        assertThat(auditOutcomes).contains("DENIED");
        assertThat(ticketRepo.findByTenant("t1")).isEmpty();
    }

    @Test
    void tenantIsolation() {
        var identity1 = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var identity2 = new AgentIdentity("t2", "u2", "s2", Set.of("user"));
        var k1 = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "iso-1");
        var k2 = IdempotencyKey.of("t2", "u2", "s2", "create_reminder_ticket", "iso-2");
        agentService.execute(AgentRequest.of(identity1, "create_reminder_ticket",
                new TicketTool.Request("kb", "x"), k1));
        agentService.execute(AgentRequest.of(identity2, "create_reminder_ticket",
                new TicketTool.Request("kb", "y"), k2));
        assertThat(ticketRepo.findByTenant("t1")).hasSize(1);
        assertThat(ticketRepo.findByTenant("t2")).hasSize(1);
    }
}