package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class H2RefundRepositoryTest {

    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final H2RefundRepository repo = new H2RefundRepository(jdbc);

    @Test
    void saveAndFind() {
        var refund = new RefundRepositoryPort.RefundRecord("REF-1", "t1", "u1", "ORD-1", 50_00L, "质量问题", "PENDING");

        repo.save(refund);

        verify(jdbc).update(contains("MERGE INTO agent_refund"), eq("REF-1"), eq("t1"), eq("u1"),
                eq("ORD-1"), eq(50_00L), eq("质量问题"), eq("PENDING"));

        var row = mock(java.sql.ResultSet.class);
        when(jdbc.query(contains("SELECT * FROM agent_refund"), any(RowMapper.class), eq("REF-1"), eq("t1")))
                .thenAnswer(inv -> {
                    RowMapper<RefundRepositoryPort.RefundRecord> mapper = inv.getArgument(1);
                    when(row.getString("refund_id")).thenReturn("REF-1");
                    when(row.getString("tenant_id")).thenReturn("t1");
                    when(row.getString("user_id")).thenReturn("u1");
                    when(row.getString("order_id")).thenReturn("ORD-1");
                    when(row.getLong("amount_cents")).thenReturn(50_00L);
                    when(row.getString("reason")).thenReturn("质量问题");
                    when(row.getString("status")).thenReturn("PENDING");
                    return List.of(mapper.mapRow(row, 0));
                });

        var found = repo.findByIdAndTenant("REF-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().refundId()).isEqualTo("REF-1");
        assertThat(found.get().status()).isEqualTo("PENDING");
        assertThat(found.get().reason()).isEqualTo("质量问题");
    }

    @Test
    void findNotFound() {
        when(jdbc.query(contains("SELECT * FROM agent_refund"), any(RowMapper.class), anyString(), anyString()))
                .thenReturn(Collections.emptyList());

        var found = repo.findByIdAndTenant("NONEXISTENT", "t1");
        assertThat(found).isEmpty();
    }

    @Test
    void countReturnsZeroWhenEmpty() {
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class))).thenReturn(0);

        assertThat(repo.count()).isZero();
    }

    @Test
    void countReturnsCorrectNumber() {
        when(jdbc.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class))).thenReturn(5);

        assertThat(repo.count()).isEqualTo(5);
    }
}