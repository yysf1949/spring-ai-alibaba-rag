package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import io.github.yysf1949.rag.agent.store.entity.CouponEntity;
import io.github.yysf1949.rag.agent.store.entity.OrderEntity;
import io.github.yysf1949.rag.agent.store.entity.RefundEntity;
import io.github.yysf1949.rag.agent.store.entity.TicketEntity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class JpaRepositoryTest {

    // ---------- Order ----------

    private final JpaOrderRepository jpaOrder = mock(JpaOrderRepository.class);
    private final JpaOrderRepositoryAdapter orderAdapter = new JpaOrderRepositoryAdapter(jpaOrder);

    @Test
    void orderSaveDelegatesToJpa() {
        var order = new OrderRepositoryPort.OrderRecord("ORD-1", "t1", "u1", 100_00L, "CREATED");
        orderAdapter.save(order);
        verify(jpaOrder).save(any(OrderEntity.class));
    }

    @Test
    void orderFindByIdAndTenantReturnsMappedRecord() {
        var entity = new OrderEntity("ORD-1", "t1", "u1", 100_00L, "CREATED");
        when(jpaOrder.findByOrderIdAndTenantId("ORD-1", "t1")).thenReturn(Optional.of(entity));

        var found = orderAdapter.findByIdAndTenant("ORD-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().orderId()).isEqualTo("ORD-1");
        assertThat(found.get().status()).isEqualTo("CREATED");
    }

    @Test
    void orderFindByIdAndTenantReturnsEmptyWhenNotFound() {
        when(jpaOrder.findByOrderIdAndTenantId("NONEXISTENT", "t1")).thenReturn(Optional.empty());
        assertThat(orderAdapter.findByIdAndTenant("NONEXISTENT", "t1")).isEmpty();
    }

    // ---------- Refund ----------

    private final JpaRefundRepository jpaRefund = mock(JpaRefundRepository.class);
    private final JpaRefundRepositoryAdapter refundAdapter = new JpaRefundRepositoryAdapter(jpaRefund);

    @Test
    void refundSaveDelegatesToJpa() {
        var refund = new RefundRepositoryPort.RefundRecord("REF-1", "t1", "u1", "ORD-1", 50_00L, "质量问题", "PENDING");
        refundAdapter.save(refund);
        verify(jpaRefund).save(any(RefundEntity.class));
    }

    @Test
    void refundFindByIdAndTenantReturnsMappedRecord() {
        var entity = new RefundEntity("REF-1", "t1", "u1", "ORD-1", 50_00L, "质量问题", "PENDING");
        when(jpaRefund.findByRefundIdAndTenantId("REF-1", "t1")).thenReturn(Optional.of(entity));

        var found = refundAdapter.findByIdAndTenant("REF-1", "t1");
        assertThat(found).isPresent();
        assertThat(found.get().refundId()).isEqualTo("REF-1");
        assertThat(found.get().reason()).isEqualTo("质量问题");
    }

    @Test
    void refundCountDelegatesToJpa() {
        when(jpaRefund.count()).thenReturn(3L);
        assertThat(refundAdapter.count()).isEqualTo(3);
    }

    // ---------- Coupon ----------

    private final JpaCouponRepository jpaCoupon = mock(JpaCouponRepository.class);
    private final JpaCouponRepositoryAdapter couponAdapter = new JpaCouponRepositoryAdapter(jpaCoupon);

    @Test
    void couponSaveDelegatesToJpa() {
        var coupon = new CouponRepositoryPort.CouponRecord("CPN-1", "t1", "u1", "ORD-1", 10_00L, "late", "ACTIVE");
        couponAdapter.save(coupon);
        verify(jpaCoupon).save(any(CouponEntity.class));
    }

    @Test
    void couponFindActiveByTenantAndUserReturnsMappedRecords() {
        var entity = new CouponEntity("CPN-1", "t1", "u1", "ORD-1", 10_00L, "late", "ACTIVE");
        when(jpaCoupon.findByTenantIdAndUserIdAndStatus("t1", "u1", "ACTIVE"))
                .thenReturn(List.of(entity));

        var found = couponAdapter.findActiveByTenantAndUser("t1", "u1");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).couponId()).isEqualTo("CPN-1");
        assertThat(found.get(0).reasonTag()).isEqualTo("late");
    }

    @Test
    void couponFindActiveByTenantAndUserReturnsEmptyWhenNone() {
        when(jpaCoupon.findByTenantIdAndUserIdAndStatus("t1", "u1", "ACTIVE"))
                .thenReturn(List.of());
        assertThat(couponAdapter.findActiveByTenantAndUser("t1", "u1")).isEmpty();
    }

    // ---------- Ticket ----------

    private final JpaTicketRepository jpaTicket = mock(JpaTicketRepository.class);
    private final JpaTicketRepositoryAdapter ticketAdapter = new JpaTicketRepositoryAdapter(jpaTicket);

    @Test
    void ticketSaveDelegatesToJpa() {
        var ticket = new TicketRepositoryPort.TicketRecord("TKT-1", "t1", "u1", "问题", "OPEN", 1000L);
        ticketAdapter.save(ticket);
        verify(jpaTicket).save(any(TicketEntity.class));
    }

    @Test
    void ticketFindByIdReturnsMappedRecord() {
        var entity = new TicketEntity("TKT-1", "t1", "u1", "问题", "OPEN", 1000L);
        when(jpaTicket.findByTicketId("TKT-1")).thenReturn(Optional.of(entity));

        var found = ticketAdapter.findById("TKT-1");
        assertThat(found).isPresent();
        assertThat(found.get().ticketId()).isEqualTo("TKT-1");
        assertThat(found.get().summary()).isEqualTo("问题");
    }

    @Test
    void ticketFindByTenantReturnsMappedRecords() {
        var entity = new TicketEntity("TKT-1", "t1", "u1", "问题", "OPEN", 1000L);
        when(jpaTicket.findByTenantId("t1")).thenReturn(List.of(entity));

        var found = ticketAdapter.findByTenant("t1");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).ticketId()).isEqualTo("TKT-1");
    }
}