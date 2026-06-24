package io.github.yysf1949.rag.agent.quota.store;

import io.github.yysf1949.rag.agent.quota.UsageMeter;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * InMemory 用量计数器 — {@code @Profile("default")} 默认激活.
 *
 * <p>用 {@code ConcurrentHashMap} 按 {@code tenantId:monthKey:resource} 索引
 * {@link AtomicLong}. 线程安全, 重启清空 (教学/demo 用).</p>
 */
@Component
@Profile("default")
public class InMemoryUsageMeter implements UsageMeter {

    private final Map<String, AtomicLong> counters = new ConcurrentHashMap<>();

    @Override
    public long increment(String tenantId, String monthKey, String resource, long delta) {
        if (delta < 0) {
            throw new IllegalArgumentException("delta must be >= 0, got: " + delta);
        }
        if (delta == 0) {
            return getCurrentUsage(tenantId, monthKey, resource);
        }
        String key = key(tenantId, monthKey, resource);
        return counters.computeIfAbsent(key, k -> new AtomicLong()).addAndGet(delta);
    }

    @Override
    public long getCurrentUsage(String tenantId, String monthKey, String resource) {
        AtomicLong v = counters.get(key(tenantId, monthKey, resource));
        return v == null ? 0L : v.get();
    }

    @Override
    public void reset(String tenantId, String monthKey) {
        counters.entrySet().removeIf(e -> {
            String[] parts = e.getKey().split(":", 3);
            return parts.length >= 2
                    && parts[0].equals(tenantId)
                    && parts[1].equals(monthKey);
        });
    }

    private static String key(String tenantId, String monthKey, String resource) {
        return tenantId + ":" + monthKey + ":" + resource;
    }
}