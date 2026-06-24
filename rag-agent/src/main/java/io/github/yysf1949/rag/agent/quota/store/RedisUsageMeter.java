package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.UsageMeter;
import io.github.yysf1949.rag.agent.store.RedisStoreFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.YearMonth;
import java.time.ZoneOffset;

/**
 * Redis 用量计数器 — {@code @Profile("redis")} 激活.
 *
 * <p>键设计:
 * <ul>
 *   <li>{@code agent:usage:{tenantId}:{monthKey}:{resource}} — 单个 counter,
 *       value 是数字字符串. 用 {@code INCRBY} (原子) + {@code EXPIRE} (月切分时 65 天后过期).</li>
 * </ul>
 * </p>
 *
 * <h2>为什么不用 Hash</h2>
 * <p>Hash 适合存一个 tenant 的多种 resource; 但跨 tenant 跨月份后, HGETALL
 * 会拉回所有 key, 不如 INCRBY 单 key 直接. 真生产多 resource 也可换 hash
 * (HSETNX + HINCRBY), 当前实现按 {@code UsageMeter} 接口粒度 (一个 resource 一个 key).</p>
 *
 * <h2>月切分过期</h2>
 * <p>{@code EXPIRE} 设为 65 天 (≈ 2 个月+5 天缓冲), 下月 1 号后老 key 自动 GC.
 * 跨月后 monthKey 变了, 写入新 key, 老 key 读出仍是上月值 (调用方应该传
 * {@link io.github.yysf1949.rag.agent.quota.TenantQuota#currentMonthKey()}).</p>
 */
@Component
@Profile("redis")
public class RedisUsageMeter implements UsageMeter {

    private static final Logger log = LoggerFactory.getLogger(RedisUsageMeter.class);

    /** 65 天 (月切分缓冲). 月底写入的 key 不会被下月 1 号立即清除. */
    private static final long KEY_TTL_SECONDS = 65L * 24L * 60L * 60L;

    private final RedisStoreFactory factory;

    public RedisUsageMeter(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public long increment(String tenantId, String monthKey, String resource, long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0, got: " + delta);
        }
        if (delta == 0) {
            return getCurrentUsage(tenantId, monthKey, resource);
        }
        try {
            // INCRBY 三段 key: usage:{tenantId}:{monthKey}:{resource}
            String key = factory.key("usage", tenantId, monthKey + ":" + resource);
            long newValue = factory.jedis().incrBy(key, delta);
            // 仅在首次创建时设过期 (节省 RTT; EXPIRE 是幂等的)
            if (newValue == delta) {
                factory.jedis().expire(key, KEY_TTL_SECONDS);
            }
            return newValue;
        } catch (Exception e) {
            log.warn("RedisUsageMeter.increment failed tenant={} month={} resource={} delta={}, "
                            + "falling back to 0",
                    tenantId, monthKey, resource, delta, e);
            // Redis 不可用时不抛 — 让 enforcer 继续 (counter 暂时不准, 下次同步修复)
            return 0L;
        }
    }

    @Override
    public long getCurrentUsage(String tenantId, String monthKey, String resource) {
        try {
            String key = factory.key("usage", tenantId, monthKey + ":" + resource);
            String v = factory.jedis().get(key);
            return v == null ? 0L : Long.parseLong(v);
        } catch (Exception e) {
            log.warn("RedisUsageMeter.getCurrentUsage failed tenant={} month={} resource={}: {}",
                    tenantId, monthKey, resource, e.getMessage());
            return 0L;
        }
    }

    @Override
    public void reset(String tenantId, String monthKey) {
        try {
            // 删整个 month 的两个 resource key (calls + tokens)
            String prefix = factory.key("usage", tenantId, monthKey) + ":";
            factory.jedis().del(
                    prefix + UsageMeter.RESOURCE_CALLS,
                    prefix + UsageMeter.RESOURCE_TOKENS
            );
        } catch (Exception e) {
            log.warn("RedisUsageMeter.reset failed tenant={} month={}: {}",
                    tenantId, monthKey, e.getMessage());
        }
    }

    /**
     * 当前 UTC 月份 key — 给非 Enforcer 调用方用.
     */
    public static String currentMonthKey() {
        return YearMonth.now(ZoneOffset.UTC).toString();
    }
}