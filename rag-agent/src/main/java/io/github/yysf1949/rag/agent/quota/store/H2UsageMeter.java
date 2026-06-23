package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.UsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * H2 用量计数器 — {@code @Profile("h2")} 激活.
 *
 * <p>每条记录 = {@code (tenant_id, month_key, resource)} 一行, {@code counter_value} 是 {@code BIGINT}.
 * 走 "update-or-insert" 模式: 先 UPDATE, 若 affected=0 再 INSERT.
 * 单实例演示用, 分布式环境应换 Redis (INCRBY 是原子的).</p>
 *
 * <h2>字段名避开 H2 保留字</h2>
 * <p>列名 {@code counter_value} (而非 {@code value}) — H2 把 {@code VALUE} 当保留字.</p>
 *
 * <h2>为什么不用 MERGE</h2>
 * <p>H2 {@code MERGE INTO ... KEY() VALUES()} 是 "replace whole row" 语义, 不支持累加.
 * 真生产 (并发高) 应换 Redis INCRBY 或 H2 的 {@code UPDATE ... SET col = col + ?} +
 * retry-on-no-affected-rows 模式 (本实现采用).</p>
 */
@Component
@Profile("h2")
public class H2UsageMeter implements UsageMeter {

    private static final Logger log = LoggerFactory.getLogger(H2UsageMeter.class);

    private final JdbcTemplate jdbc;

    public H2UsageMeter(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public long increment(String tenantId, String monthKey, String resource, long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0, got: " + delta);
        }
        if (delta == 0) {
            return getCurrentUsage(tenantId, monthKey, resource);
        }
        // 1) 尝试 UPDATE 累加 (行存在时生效)
        int updated = jdbc.update(
                "UPDATE agent_usage_counter SET counter_value = counter_value + ? "
                        + "WHERE tenant_id = ? AND month_key = ? AND resource = ?",
                delta, tenantId, monthKey, resource);
        if (updated == 0) {
            // 2) 行不存在 → INSERT 新行 (delta 作为初始值)
            // 注: 多线程并发可能都走到这里, 但 H2 内嵌默认单连接, 教学场景下不撞车.
            jdbc.update(
                    "INSERT INTO agent_usage_counter "
                            + "(tenant_id, month_key, resource, counter_value) VALUES (?, ?, ?, ?)",
                    tenantId, monthKey, resource, delta);
        }
        // 读回最新值
        return getCurrentUsage(tenantId, monthKey, resource);
    }

    @Override
    public long getCurrentUsage(String tenantId, String monthKey, String resource) {
        try {
            Long v = jdbc.queryForObject(
                    "SELECT counter_value FROM agent_usage_counter "
                            + "WHERE tenant_id = ? AND month_key = ? AND resource = ?",
                    Long.class, tenantId, monthKey, resource);
            return v == null ? 0L : v;
        } catch (org.springframework.dao.EmptyResultDataAccessException e) {
            return 0L;
        }
    }

    @Override
    public void reset(String tenantId, String monthKey) {
        int n = jdbc.update(
                "DELETE FROM agent_usage_counter "
                        + "WHERE tenant_id = ? AND month_key = ?",
                tenantId, monthKey);
        log.debug("H2UsageMeter.reset tenant={} month={} deleted={}", tenantId, monthKey, n);
    }
}