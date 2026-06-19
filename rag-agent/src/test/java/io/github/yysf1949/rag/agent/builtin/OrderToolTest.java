package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemoryOrderRepository;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.InMemoryIdempotencyStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderToolTest {

    private InMemoryOrderRepository repo;
    private InMemoryIdempotencyStore idemStore;
    private OrderTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemoryOrderRepository();
        idemStore = new InMemoryIdempotencyStore();
        // 预置数据 — CREATED 状态才能被 cancel（plan 业务规则要求只 CREATED/PAID 可取消）
        repo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-1", "tenant-1", "user-1", 100_00L, "CREATED"));
        tool = new OrderTool(repo, idemStore);
    }

    private IdempotencyKey key(String token) {
        return IdempotencyKey.of("tenant-1", "user-1", "s1", "cancel_order", token);
    }

    @Test
    void getOrderByIdFound() {
        var resp = tool.getOrder(new OrderTool.GetOrderRequest(
                "tenant-1", "user-1", "ORD-1"));
        assertThat(resp.orderId()).isEqualTo("ORD-1");
        assertThat(resp.status()).isEqualTo("CREATED");
        assertThat(resp.amountCents()).isEqualTo(100_00L);
    }

    @Test
    void getOrderByIdCrossTenantBlocked() {
        assertThatThrownBy(() ->
                tool.getOrder(new OrderTool.GetOrderRequest(
                        "tenant-2", "user-1", "ORD-1")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ORD-1");
    }

    @Test
    void cancelOrderHappyPath() {
        var resp = tool.cancelOrder(key("cancel-1"),
                new OrderTool.CancelOrderRequest(
                        "tenant-1", "user-1", "ORD-1", 100_00L, "用户主动取消"));
        assertThat(resp.orderId()).isEqualTo("ORD-1");
        assertThat(resp.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOrderOnAlreadyCancelledIdempotent() {
        // 第一次取消
        tool.cancelOrder(key("cancel-idem"),
                new OrderTool.CancelOrderRequest(
                        "tenant-1", "user-1", "ORD-1", 100_00L, "first"));
        // 第二次幂等
        var resp = tool.cancelOrder(key("cancel-idem"),
                new OrderTool.CancelOrderRequest(
                        "tenant-1", "user-1", "ORD-1", 100_00L, "second"));
        assertThat(resp.status()).isEqualTo("CANCELLED");
    }

    @Test
    void cancelOrderOnShippedFails() {
        // 已发货订单不能取消
        repo.save(new OrderRepositoryPort.OrderRecord(
                "ORD-2", "tenant-1", "user-1", 50_00L, "DELIVERED"));
        assertThatThrownBy(() ->
                tool.cancelOrder(key("cancel-fail"),
                        new OrderTool.CancelOrderRequest(
                                "tenant-1", "user-1", "ORD-2", 50_00L, "try cancel delivered")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DELIVERED");
    }
}
