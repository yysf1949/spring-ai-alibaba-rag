package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.builtin.InMemoryTicketRepository;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.TicketTool;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.DefaultRiskGate;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultAgentLoopTest {

    private DefaultAgentLoop loop;
    private ToolRegistry registry;
    private List<ToolInvocationContext> auditTrail;

    @BeforeEach
    void setUp() {
        QAService qa = mock(QAService.class);
        // Answer 字段顺序: tenantId, queryHash, rewrittenQuery, retrieved, reranked,
        //   finalText, citations, source, latencyMs, metrics
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

        auditTrail = new ArrayList<>();
        ToolAuditBridge bridge = new ToolAuditBridge(new LlmAuditHook() {
            @Override public void onLlmCall(String t, String u, String s, String q, String m, String pt, String pb, String c, long l, String o) {
                auditTrail.add(new ToolInvocationContext(new AgentIdentity(t, u, s, Set.of()), m, pb, c, l, o));
            }
        });
        IdempotencyStore idem = new InMemoryIdempotencyStore();
        RiskGate gate = new DefaultRiskGate();
        loop = new DefaultAgentLoop(registry, gate, idem, bridge);
    }

    @Test
    void executesToolByName() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var req = AgentRequest.of(identity, "kb_search",
                new KbSearchTool.Request("t1", "u1", "怎么退款", Set.of(), 5, null),
                null);
        AgentResponse resp = loop.execute(req);
        assertThat(resp.outcome()).isEqualTo("SUCCESS");
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
        assertThatThrownBy(() -> loop.execute(req))
                .isInstanceOf(ToolRiskDeniedException.class);
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
}