package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 提醒工单内存仓库 — 教学 demo 用。
 *
 * <h2>升级路径</h2>
 * <p>生产可换 MySQL/Postgres + Flyway 迁移。本 Phase 范围只覆盖 L2 写操作的
 * 幂等 + 审计 + 风险门控的端到端跑通。</p>
 */
@Component
@Profile("default")
public class InMemoryTicketRepository implements TicketRepositoryPort {

    private final Map<String, TicketRepositoryPort.TicketRecord> store = new ConcurrentHashMap<>();

    @Override
    public TicketRepositoryPort.TicketRecord save(TicketRepositoryPort.TicketRecord t) {
        store.put(t.ticketId(), t);
        return t;
    }

    @Override
    public Optional<TicketRepositoryPort.TicketRecord> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    @Override
    public List<TicketRepositoryPort.TicketRecord> findByTenant(String tenantId) {
        List<TicketRepositoryPort.TicketRecord> out = new ArrayList<>();
        for (TicketRepositoryPort.TicketRecord t : store.values()) {
            if (t.tenantId().equals(tenantId)) out.add(t);
        }
        return out;
    }
}
