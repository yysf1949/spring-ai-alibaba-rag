package io.github.yysf1949.rag.agent.quota;

/**
 * 租户用量计数器 — Phase 40 T3 (R11: 商业化第一阶段).
 *
 * <h2>设计意图</h2>
 * <p>按 {@code (tenantId, monthKey, resource)} 三维计数 — 调用前由
 * {@link TenantQuotaEnforcer} 读 {@link #getCurrentUsage} 检查上限，
 * 调用成功后由 Enforcer 或上层调用 {@link #increment} 计入。资源类型
 * "calls" / "tokens" 写死成常量，便于 Prometheus / Micrometer 拉取。</p>
 *
 * <h2>月切分</h2>
 * <p>counter 按 {@link TenantQuota#currentMonthKey()} 分桶。下月首次
 * 访问自动得到 0（旧 key 永久保留，但 quota 实体按月重建）。</p>
 *
 * <h2>实现选择</h2>
 * <ul>
 *   <li>{@code @Profile("default")} → {@code InMemoryUsageMeter} (ConcurrentHashMap)</li>
 *   <li>{@code @Profile("h2")} → {@code H2UsageMeter} (JdbcTemplate, 单实例演示)</li>
 *   <li>{@code @Profile("redis")} → {@code RedisUsageMeter} (INCR + EXPIRE)</li>
 * </ul>
 */
public interface UsageMeter {

    /** 资源类型常量 — 写入 counter 的 resource 维度。 */
    String RESOURCE_CALLS = "calls";
    String RESOURCE_TOKENS = "tokens";

    /**
     * 给指定 tenant 指定月 指定 resource 增加 delta，返回加完后的值。
     * delta 必须非负 (避免滥用为扣减)。
     */
    long increment(String tenantId, String monthKey, String resource, long delta);

    /**
     * 查询当前用量。如果该月该 tenant 该 resource 还没创建 counter，返回 0。
     */
    long getCurrentUsage(String tenantId, String monthKey, String resource);

    /**
     * 重置某 tenant 某月所有 resource counter — 主要给测试用，
     * 也给定时任务 (下月 1 号 UTC 跨月后清理旧 key) 用。
     */
    void reset(String tenantId, String monthKey);
}