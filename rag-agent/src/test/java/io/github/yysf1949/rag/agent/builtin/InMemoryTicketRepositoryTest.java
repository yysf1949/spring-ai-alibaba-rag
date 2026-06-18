package io.github.yysf1949.rag.agent.builtin;

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
        // Ticket record 字段: ticketId, tenantId, userId, sourceTool, description, status, createdAt
        // plan 测试代码只给了 6 个参数 (漏了 tenantId)，与 record 7 字段不匹配；
        // 按 plan 意图补 tenantId="t1"，"PENDING" 之后那个 null 是 createdAt。
        var t = new TicketTool.Ticket("t1", "t1", "u1", "kb-search", "查询结果为空", "PENDING", null);
        repo.save(t);
        assertThat(repo.findById("t1")).isPresent();
    }

    @Test
    void findByTenantFilters() {
        // 同上：补 tenantId 字段
        repo.save(new TicketTool.Ticket("t1", "t1", "u1", "kb-search", "x", "PENDING", null));
        repo.save(new TicketTool.Ticket("t2", "t2", "u2", "kb-search", "y", "PENDING", null));
        assertThat(repo.findByTenant("t1")).hasSize(1);
        assertThat(repo.findByTenant("t2")).hasSize(1);
        assertThat(repo.findByTenant("t3")).isEmpty();
    }
}
