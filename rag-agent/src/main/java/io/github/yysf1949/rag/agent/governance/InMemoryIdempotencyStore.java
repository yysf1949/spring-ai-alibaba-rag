package io.github.yysf1949.rag.agent.governance;

import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 内存版 IdempotencyStore — 单实例部署够用；分布式部署可换 Redis 实现。
 *
 * <h2>升级路径</h2>
 * <p>生产建议：把 {@code Map} 换成 {@code rag-redis} 里的
 * {@code SETNX + EXPIRE} 模式（5 分钟 TTL 防膨胀）。</p>
 *
 * <h2>为什么不用 ConcurrentHashMap</h2>
 * <p>{@code CHM.putIfAbsent} 和 {@code put} 都不接受 {@code null} value，
 * 但 {@code TicketTool.createReminder} 第一次写入时会传 {@code null} 占位
 * （plan 设计的"先占锁 → 业务完成后回填"模式）。所以这里改用
 * {@code HashMap + ReentrantLock}，显式支持 null value。</p>
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final Map<String, Object> store = new HashMap<>();
    private final ReentrantLock lock = new ReentrantLock();

    @Override
    public PutResult putIfAbsent(IdempotencyKey key, Object value) {
        String hash = key.hash();
        lock.lock();
        try {
            if (!store.containsKey(hash)) {
                store.put(hash, value);
                return new PutResult(PutResult.OutcomeKind.FIRST, value);
            }
            return new PutResult(PutResult.OutcomeKind.REPLAY, store.get(hash));
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void replace(IdempotencyKey key, Object value) {
        String hash = key.hash();
        lock.lock();
        try {
            // 覆盖写入（即使 value 是 null 也允许 — 业务可能要"撤销"占位）
            store.put(hash, value);
        } finally {
            lock.unlock();
        }
    }
}
