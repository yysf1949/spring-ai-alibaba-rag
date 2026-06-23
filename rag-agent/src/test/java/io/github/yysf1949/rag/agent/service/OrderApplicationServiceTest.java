package io.github.yysf1949.rag.agent.service;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort.OrderRecord;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryOrderRepository;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderApplicationServiceTest {

    private InMemoryOrderRepository repo;
    private InMemoryIdempotencyStore idemStore;
    private OrderApplicationService service;

    @BeforeEach
    void setUp() {
        repo = new InMemoryOrderRepository();
        idemStore = new InMemoryIdempotencyStore();
        var metrics = new AgentMetrics(new SimpleMeterRegistry());
        service = new OrderApplicationService(repo, idemStore, metrics);

        // 预置数据 — CREATED 状态可以被取消
        repo.save(new OrderRecord("ORD-1", "tenant-1", "user-1", 100_00L, "CREATED"));
    }

    private IdempotencyKey key(String token) {
        return IdempotencyKey.of("tenant-1", "user-1", "s1", "cancel_order", token);
    }

    @Test
    void cancelOrderNormalFlow() {
        OrderRecord cancelled = service.cancelOrder(
                "tenant-1", "user-1", "ORD-1", 100_00L, "用户主动取消", key("cancel-1"));

        assertThat(cancelled.orderId()).isEqualTo("ORD-1");
        assertThat(cancelled.status()).isEqualTo("CANCELLED");

        // 验证 repo 状态已更新
        var stored = repo.findByIdAndTenant("ORD-1", "tenant-1");
        assertThat(stored).isPresent();
        assertThat(stored.get().status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOrderIdempotentReturnsSameResult() {
        IdempotencyKey idemKey = key("cancel-idem");

        OrderRecord first = service.cancelOrder(
                "tenant-1", "user-1", "ORD-1", 100_00L, "第一次", idemKey);
        OrderRecord second = service.cancelOrder(
                "tenant-1", "user-1", "ORD-1", 100_00L, "第二次", idemKey);

        assertThat(first.orderId()).isEqualTo(second.orderId());
        assertThat(first.status()).isEqualTo("CANCELLED");
        assertThat(second.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOrderInvalidStatusThrowsException() {
        // DELIVERED 状态不能取消
        repo.save(new OrderRecord("ORD-2", "tenant-1", "user-1", 50_00L, "DELIVERED"));

        assertThatThrownBy(() ->
                service.cancelOrder(
                        "tenant-1", "user-1", "ORD-2", 50_00L, "try cancel", key("cancel-fail")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERED");
    }

    @Test
    void cancelOrderShippedStatusThrowsException() {
        // SHIPPED 状态也不能取消
        repo.save(new OrderRecord("ORD-3", "tenant-1", "user-1", 80_00L, "SHIPPED"));

        assertThatThrownBy(() ->
                service.cancelOrder(
                        "tenant-1", "user-1", "ORD-3", 80_00L, "try cancel", key("cancel-fail2")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SHIPPED");
    }

    @Test
    void getOrderReturnsOrderWhenFound() {
        OrderRecord order = service.getOrder("tenant-1", "ORD-1");

        assertThat(order.orderId()).isEqualTo("ORD-1");
        assertThat(order.status()).isEqualTo("CREATED");
        assertThat(order.amountCents()).isEqualTo(100_00L);
        assertThat(order.userId()).isEqualTo("user-1");
    }

    @Test
    void getOrderThrowsWhenNotFound() {
        assertThatThrownBy(() -> service.getOrder("tenant-1", "ORD-999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORD-999");
    }

    @Test
    void listOrdersReturnsUserOrders() {
        repo.save(new OrderRecord("ORD-2", "tenant-1", "user-1", 200_00L, "PAID"));
        repo.save(new OrderRecord("ORD-3", "tenant-1", "user-2", 50_00L, "CREATED"));

        var orders = service.listOrders("tenant-1", "user-1");

        assertThat(orders).hasSize(2);
        assertThat(orders).extracting(OrderRecord::orderId)
                .containsExactlyInAnyOrder("ORD-1", "ORD-2");
    }

    @Test
    void listOrdersReturnsEmptyWhenNoOrders() {
        var orders = service.listOrders("tenant-1", "ghost-user");
        assertThat(orders).isEmpty();
    }
}
