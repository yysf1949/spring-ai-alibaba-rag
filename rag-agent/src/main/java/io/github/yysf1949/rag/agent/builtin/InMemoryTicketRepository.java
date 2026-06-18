package io.github.yysf1949.rag.agent.builtin;

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
public class InMemoryTicketRepository {

    private final Map<String, TicketTool.Ticket> store = new ConcurrentHashMap<>();

    public TicketTool.Ticket save(TicketTool.Ticket t) {
        store.put(t.ticketId(), t);
        return t;
    }

    public Optional<TicketTool.Ticket> findById(String id) {
        return Optional.ofNullable(store.get(id));
    }

    public List<TicketTool.Ticket> findByTenant(String tenantId) {
        List<TicketTool.Ticket> out = new ArrayList<>();
        for (TicketTool.Ticket t : store.values()) {
            if (t.tenantId().equals(tenantId)) out.add(t);
        }
        return out;
    }
}
