package io.github.yysf1949.rag.agent.builtin.store;

import io.github.yysf1949.rag.agent.builtin.port.AfterServiceAuditPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 售后善后审计内存仓库 — 教学 demo 用。
 *
 * <h2>升级路径</h2>
 * <p>生产可换 MySQL/Postgres + Flyway 迁移。本 Phase 范围只覆盖售后善后审计的
 * 端到端跑通。</p>
 */
@Component
public class InMemoryAfterServiceAuditRepository implements AfterServiceAuditPort {

    private final Map<String, AfterServiceAuditPort.AuditRecord> store = new ConcurrentHashMap<>();

    @Override
    public AfterServiceAuditPort.AuditRecord save(AfterServiceAuditPort.AuditRecord record) {
        store.put(record.auditId(), record);
        return record;
    }

    @Override
    public List<AfterServiceAuditPort.AuditRecord> findByOrder(String orderId) {
        List<AfterServiceAuditPort.AuditRecord> out = new ArrayList<>();
        for (AfterServiceAuditPort.AuditRecord r : store.values()) {
            if (r.orderId().equals(orderId)) {
                out.add(r);
            }
        }
        return out;
    }
}
