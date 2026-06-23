package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryTicketRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryTicketRepositoryTest {

    private InMemoryTicketRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryTicketRepository();
    }

    @Test
    void saveAndFind() {
        var t = new TicketRepositoryPort.TicketRecord("t1", "t1", "u1", "查询结果为空", "PENDING", 0L);
        repo.save(t);
        assertThat(repo.findById("t1")).isPresent();
    }

    @Test
    void findByTenantFilters() {
        repo.save(new TicketRepositoryPort.TicketRecord("t1", "t1", "u1", "x", "PENDING", 0L));
        repo.save(new TicketRepositoryPort.TicketRecord("t2", "t2", "u2", "y", "PENDING", 0L));
        assertThat(repo.findByTenant("t1")).hasSize(1);
        assertThat(repo.findByTenant("t2")).hasSize(1);
        assertThat(repo.findByTenant("t3")).isEmpty();
    }
}