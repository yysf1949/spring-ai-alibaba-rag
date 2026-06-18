package io.github.yysf1949.rag.agent.governance;

import org.springframework.stereotype.Component;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 内存版 IdempotencyStore — 单实例部署够用；分布式部署可换 Redis 实现。
 *
 * <h2>升级路径</h2>
 * <p>生产建议：把 {@code Map} 换成 {@code rag-redis} 里的
 * {@code SETNX + EXPIRE} 模式（5 分钟 TTL 防膨胀）。</p>
 */
@Component
public class InMemoryIdempotencyStore implements IdempotencyStore {

    private final ConcurrentMap<String, Object> store = new ConcurrentHashMap<>();

    @Override
    public PutResult putIfAbsent(IdempotencyKey key, Object value) {
        Object existing = store.putIfAbsent(key.hash(), value);
        if (existing == null) {
            return new PutResult(PutResult.OutcomeKind.FIRST, value);
        }
        return new PutResult(PutResult.OutcomeKind.REPLAY, existing);
    }
}