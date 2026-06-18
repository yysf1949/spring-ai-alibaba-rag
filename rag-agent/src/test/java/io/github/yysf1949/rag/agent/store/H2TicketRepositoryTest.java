package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class H2TicketRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final H2TicketRepository repo = new H2TicketRepository(jdbc);

    @Test
    void saveAndFindById() {
        var ticket = new TicketRepositoryPort.TicketRecord("TKT-1", "t1", "u1", "问题已解决", "CLOSED", 1700000000000L);

        repo.save(ticket);

        verify(jdbc).update(contains("MERGE INTO agent_ticket"), eq("TKT-1"), eq("t1"), eq("u1"),
                eq("问题已解决"), eq("CLOSED"), eq(1700000000000L));

        var row = mock(java.sql.ResultSet.class);
        when(jdbc.query(contains("SELECT * FROM agent_ticket WHERE ticket_id = ?"), any(RowMapper.class), eq("TKT-1")))
                .thenAnswer(inv -> {
                    RowMapper<TicketRepositoryPort.TicketRecord> mapper = inv.getArgument(1);
                    when(row.getString("ticket_id")).thenReturn("TKT-1");
                    when(row.getString("tenant_id")).thenReturn("t1");
                    when(row.getString("user_id")).thenReturn("u1");
                    when(row.getString("summary")).thenReturn("问题已解决");
                    when(row.getString("status")).thenReturn("CLOSED");
                    when(row.getLong("created_at")).thenReturn(1700000000000L);
                    return List.of(mapper.mapRow(row, 0));
                });

        var found = repo.findById("TKT-1");
        assertThat(found).isPresent();
        assertThat(found.get().ticketId()).isEqualTo("TKT-1");
        assertThat(found.get().createdAt()).isEqualTo(1700000000000L);
    }

    @Test
    void findByIdNotFound() {
        when(jdbc.query(contains("SELECT * FROM agent_ticket WHERE ticket_id = ?"), any(RowMapper.class), anyString()))
                .thenReturn(Collections.emptyList());

        var found = repo.findById("NONEXISTENT");
        assertThat(found).isEmpty();
    }

    @Test
    void findByTenant() {
        var row1 = mock(java.sql.ResultSet.class);
        var row2 = mock(java.sql.ResultSet.class);
        when(jdbc.query(contains("SELECT * FROM agent_ticket WHERE tenant_id = ?"), any(RowMapper.class), eq("t1")))
                .thenAnswer(inv -> {
                    RowMapper<TicketRepositoryPort.TicketRecord> mapper = inv.getArgument(1);
                    when(row1.getString("ticket_id")).thenReturn("TKT-1");
                    when(row1.getString("tenant_id")).thenReturn("t1");
                    when(row1.getString("user_id")).thenReturn("u1");
                    when(row1.getString("summary")).thenReturn("问题1");
                    when(row1.getString("status")).thenReturn("OPEN");
                    when(row1.getLong("created_at")).thenReturn(1700000000000L);

                    when(row2.getString("ticket_id")).thenReturn("TKT-2");
                    when(row2.getString("tenant_id")).thenReturn("t1");
                    when(row2.getString("user_id")).thenReturn("u2");
                    when(row2.getString("summary")).thenReturn("问题2");
                    when(row2.getString("status")).thenReturn("OPEN");
                    when(row2.getLong("created_at")).thenReturn(1700000001000L);

                    return List.of(mapper.mapRow(row1, 0), mapper.mapRow(row2, 0));
                });

        var tickets = repo.findByTenant("t1");
        assertThat(tickets).hasSize(2);
        assertThat(tickets).extracting(TicketRepositoryPort.TicketRecord::ticketId)
                .containsExactly("TKT-1", "TKT-2");
    }
}