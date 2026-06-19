package io.github.yysf1949.rag.agent.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存版 IdempotencyStore — 单实例部署够用；分布式部署可换 Redis 实现。
 *
 * <h2>TTL 防膨胀</h2>
 * <p>每条记录带写入时间戳，后台定时任务每 60 秒清理超过 TTL（默认 5 分钟）
 * 的过期条目，防止无限增长导致 OOM。</p>
 *
 * <h2>升级路径</h2>
 * <p>生产建议：把 {@code Map} 换成 {@code rag-redis} 里的
 * {@code SETNX + EXPIRE} 模式（5 分钟 TTL 防膨胀）。</p>
 *
 * <h2>为什么不用 ConcurrentHashMap.putIfAbsent</h2>
 * <p>{@code CHM.putIfAbsent} 不接受 {@code null} value，
 * 但 {@code TicketTool.createReminder} 第一次写入时会传 {@code null} 占位
 * （"先占锁 → 业务完成后回填"模式）。所以这里改用
 * {@code ConcurrentHashMap + ReentrantLock}，显式支持 null value。</p>
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private static final Logger log = LoggerFactory.getLogger(InMemoryIdempotencyStore.class);

    /** 默认 TTL：5 分钟 */
    private static final long DEFAULT_TTL_MS = 5 * 60 * 1000L;

    private final ConcurrentHashMap<String, Entry> store = new ConcurrentHashMap<>();
    private final ReentrantLock lock = new ReentrantLock();
    private final long ttlMs;

    public InMemoryIdempotencyStore() {
        this(DEFAULT_TTL_MS);
    }

    public InMemoryIdempotencyStore(long ttlMs) {
        this.ttlMs = ttlMs;
    }

    @Override
    public PutResult putIfAbsent(IdempotencyKey key, Object value) {
        String hash = key.hash();
        lock.lock();
        try {
            Entry existing = store.get(hash);
            if (existing == null || existing.isExpired(ttlMs)) {
                store.put(hash, new Entry(value, System.currentTimeMillis()));
                return new PutResult(PutResult.OutcomeKind.FIRST, value);
            }
            return new PutResult(PutResult.OutcomeKind.REPLAY, existing.value);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void replace(IdempotencyKey key, Object value) {
        String hash = key.hash();
        lock.lock();
        try {
            store.put(hash, new Entry(value, System.currentTimeMillis()));
        } finally {
            lock.unlock();
        }
    }

    /**
     * 定时清理过期条目，防止内存无限增长。
     * 每 60 秒执行一次。
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpired() {
        long now = System.currentTimeMillis();
        int removed = 0;
        var it = store.entrySet().iterator();
        while (it.hasNext()) {
            var entry = it.next();
            if (entry.getValue().isExpired(ttlMs, now)) {
                it.remove();
                removed++;
            }
        }
        if (removed > 0) {
            log.debug("Evicted {} expired idempotency entries (store size: {})", removed, store.size());
        }
    }

    /** 当前 store 大小（监控/测试用） */
    public int size() {
        return store.size();
    }

    private record Entry(Object value, long createdAt) {
        boolean isExpired(long ttlMs) {
            return isExpired(ttlMs, System.currentTimeMillis());
        }

        boolean isExpired(long ttlMs, long now) {
            return (now - createdAt) > ttlMs;
        }
    }
}
