package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.TenantQuota;
import io.github.yysf1949.rag.agent.quota.TenantQuotaPort;
import io.github.yysf1949.rag.agent.quota.TenantTier;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * H2 Tenant 配额仓库 — {@code @Profile("h2")} 激活.
 *
 * <p>DDL 见 {@code rag-agent/src/main/resources/schema-h2.sql} (Phase 40 T3 新增 {@code agent_tenant_quota}).
 * 使用 H2 {@code MERGE INTO} 做 upsert. tier 用 {@code VARCHAR} 存 enum 名.</p>
 *
 * <h2>时间字段</h2>
 * <p>effectiveFrom / effectiveTo / downgradedAt 全部 {@code BIGINT} epoch millis,
 * 跟 Feedback 表保持一致 (避免时区陷阱).</p>
 */
@Component
@Profile("h2")
public class H2TenantQuotaRepository implements TenantQuotaPort {

    private final JdbcTemplate jdbc;

    private static final RowMapper<TenantQuota> MAPPER = (rs, row) -> hydrate(rs);

    public H2TenantQuotaRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<TenantQuota> findById(String tenantId) {
        var list = jdbc.query(
                "SELECT * FROM agent_tenant_quota WHERE tenant_id = ?",
                MAPPER, tenantId);
        return list.stream().findFirst();
    }

    @Override
    public TenantQuota save(TenantQuota quota) {
        jdbc.update(
                "MERGE INTO agent_tenant_quota ("
                        + "tenant_id, tier, monthly_call_limit, monthly_token_limit, "
                        + "effective_from, effective_to, downgraded_at, original_tier) "
                        + "KEY(tenant_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                quota.tenantId(),
                quota.tier().name(),
                quota.monthlyCallLimit(),
                quota.monthlyTokenLimit(),
                quota.effectiveFrom(),
                quota.effectiveTo(),
                quota.downgradedAt(),
                quota.originalTier() == null ? null : quota.originalTier().name()
        );
        return quota;
    }

    @Override
    public List<TenantQuota> findAll() {
        return jdbc.query("SELECT * FROM agent_tenant_quota ORDER BY tenant_id", MAPPER);
    }

    @Override
    public TenantQuota clearDowngrade(String tenantId) {
        Optional<TenantQuota> cur = findById(tenantId);
        TenantQuota target;
        if (cur.isEmpty()) {
            target = TenantQuota.forNewTenant(tenantId, TenantTier.FREE, System.currentTimeMillis());
        } else {
            target = cur.get().clearedDowngrade();
        }
        return save(target);
    }

    private static TenantQuota hydrate(ResultSet rs) throws SQLException {
        Long effectiveTo = rs.getLong("effective_to");
        if (rs.wasNull()) effectiveTo = null;
        Long downgradedAt = rs.getLong("downgraded_at");
        if (rs.wasNull()) downgradedAt = null;
        String originalTierName = rs.getString("original_tier");
        TenantTier originalTier = originalTierName == null ? null : TenantTier.valueOf(originalTierName);
        return new TenantQuota(
                rs.getString("tenant_id"),
                TenantTier.valueOf(rs.getString("tier")),
                rs.getLong("monthly_call_limit"),
                rs.getLong("monthly_token_limit"),
                rs.getLong("effective_from"),
                effectiveTo,
                downgradedAt,
                originalTier
        );
    }
}