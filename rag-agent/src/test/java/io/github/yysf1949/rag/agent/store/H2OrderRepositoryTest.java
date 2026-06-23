package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class H2OrderRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final H2OrderRepository repo = new H2OrderRepository(jdbc);

    @Test
    void saveAndFind() {
        var order = new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "u1", 100_00L, "CREATED");

        repo.save(order);

        verify(jdbc).update(contains("MERGE INTO agent_order"), eq("ORD-1"), eq("t1"), eq("u1"), eq(100_00L), eq("CREATED"));

        var row = mock(java.sql.ResultSet.class);
        when(jdbc.query(contains("SELECT * FROM agent_order"), any(RowMapper.class), eq("ORD-1"), eq("t1")))
                .thenAnswer(inv -> {
                    RowMapper<OrderRepositoryPort.OrderRecord> mapper = inv.getArgument(1);
                    when(row.getString("order_id")).thenReturn("ORD-1");
                    when(row.getString("tenant_id")).thenReturn("t1");
                    when(row.getString("user_id")).thenReturn("u1");
                    when(row.getLong("amount_cents")).thenReturn(100_00L);
                    when(row.getString("status")).thenReturn("CREATED");
                    return List.of(mapper.mapRow(row, 0));
                });

        var found = repo.findByIdAndTenant("ORD-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().orderId()).isEqualTo("ORD-1");
        assertThat(found.get().status()).isEqualTo("CREATED");
    }

    @Test
    void findNotFound() {
        when(jdbc.query(contains("SELECT * FROM agent_order"), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        var found = repo.findByIdAndTenant("NONEXISTENT", "t1");
        assertThat(found).isEmpty();
    }

    @Test
    void overwriteExisting() {
        var order1 = new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "u1", 100_00L, "CREATED");
        var order2 = new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "u1", 200_00L, "PAID");

        repo.save(order1);
        repo.save(order2);

        verify(jdbc, times(2)).update(contains("MERGE INTO agent_order"), any(), any(), any(), any(), any());

        var row = mock(java.sql.ResultSet.class);
        when(jdbc.query(contains("SELECT * FROM agent_order"), any(RowMapper.class), eq("ORD-1"), eq("t1")))
                .thenAnswer(inv -> {
                    RowMapper<OrderRepositoryPort.OrderRecord> mapper = inv.getArgument(1);
                    when(row.getString("order_id")).thenReturn("ORD-1");
                    when(row.getString("tenant_id")).thenReturn("t1");
                    when(row.getString("user_id")).thenReturn("u1");
                    when(row.getLong("amount_cents")).thenReturn(200_00L);
                    when(row.getString("status")).thenReturn("PAID");
                    return List.of(mapper.mapRow(row, 0));
                });

        var found = repo.findByIdAndTenant("ORD-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().amountCents()).isEqualTo(200_00L);
        assertThat(found.get().status()).isEqualTo("PAID");
    }
}