package io.github.yysf1949.rag.agent.builtin.store;

import io.github.yysf1949.rag.agent.builtin.port.OrderRepositoryPort;
import org.springframework.stereotype.Repository;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版订单仓储 — 演示用。
 *
 * <h2>为什么不直连数据库</h2>
 * <p>本项目是 RAG 引擎，订单/退款等业务系统是外部依赖。本类用内存版模拟
 * 业务 Service 行为 — 真实生产应该走 port-and-adapter，从 {@code rag-pipeline}
 * 注入真实 OrderService 实现。</p>
 *
 * <h2>租户硬墙</h2>
 * <p>{@code findByIdAndTenant} 强制 tenantId 匹配 — 跨租户查询直接抛
 * IllegalArgumentException，对齐「路条编程」文章 §"Agent 不能绕过原有业务规则"。</p>
 */
@Repository
public class InMemoryOrderRepository implements OrderRepositoryPort {

    private final Map<String, OrderRepositoryPort.OrderRecord> store = new ConcurrentHashMap<>();

    @Override
    public OrderRepositoryPort.OrderRecord save(OrderRepositoryPort.OrderRecord order) {
        store.put(order.orderId(), order);
        return order;
    }

    @Override
    public Optional<OrderRepositoryPort.OrderRecord> findByIdAndTenant(String orderId, String tenantId) {
        OrderRepositoryPort.OrderRecord o = store.get(orderId);
        if (o == null) return Optional.empty();
        if (!o.tenantId().equals(tenantId)) {
            throw new IllegalArgumentException(
                    "Cross-tenant access denied: requested by tenant=" + tenantId
                            + " but order belongs to tenant=" + o.tenantId());
        }
        return Optional.of(o);
    }
}
