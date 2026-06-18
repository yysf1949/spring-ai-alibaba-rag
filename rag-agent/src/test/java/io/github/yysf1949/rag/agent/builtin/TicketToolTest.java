package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.store.InMemoryTicketRepository;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class TicketToolTest {

    private InMemoryTicketRepository repo;
    private IdempotencyStore idem;
    private TicketTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryTicketRepository();
        idem = new InMemoryIdempotencyStore();
        tool = new TicketTool(repo, idem);
    }

    @Test
    void createsTicketOnFirstCall() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", UUID.randomUUID().toString());
        var req = new TicketTool.Request("kb-search", "查询结果为空，请人工跟进");

        var resp = tool.createReminder(identity, key, req);

        assertThat(resp.ticketId()).isNotBlank();
        assertThat(resp.status()).isEqualTo("PENDING");
        assertThat(repo.findById(resp.ticketId())).isPresent();
    }

    @Test
    void sameIdempotencyKeyReturnsSameTicket() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var key = IdempotencyKey.of("t1", "u1", "s1", "create_reminder_ticket", "stable-token-123");
        var req = new TicketTool.Request("kb-search", "query");

        var resp1 = tool.createReminder(identity, key, req);
        var resp2 = tool.createReminder(identity, key, req);

        assertThat(resp1.ticketId()).isEqualTo(resp2.ticketId());
        assertThat(repo.findByTenant("t1")).hasSize(1);
    }
}