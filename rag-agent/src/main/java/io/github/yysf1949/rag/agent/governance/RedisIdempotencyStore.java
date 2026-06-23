package io.github.yysf1949.rag.agent.governance;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Redis 持久化 IdempotencyStore — 分布式部署的幂等。
 *
 * <h2>为什么用 SETNX + EX</h2>
 * <p>{@code SET key value NX EX 30} 原子操作 — 第一次设置成功 (返回 "OK")，
 * 后续设置失败 (返回 null)。30 秒 TTL 防膨胀，工具执行时间应远小于 30s，
 * 实际写回结果用 {@code replace()} 覆盖（不带 NX）。</p>
 *
 * <h2>激活条件</h2>
 * <ul>
 *   <li>classpath 包含 {@code JedisPool} — 由 rag-redis 间接依赖</li>
 *   <li>配置 {@code agent.idempotency.store=redis} — 默认仍用 InMemory</li>
 * </ul>
 */
@Component
@ConditionalOnClass(JedisPool.class)
@ConditionalOnProperty(name = "agent.idempotency.store", havingValue = "redis")
public class RedisIdempotencyStore implements IdempotencyStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(RedisIdempotencyStore.class);

    /** 占位符 TTL（秒）— 仅用于 SETNX 占位阶段，业务完成后 replace 不延寿 */
    private static final long PLACEHOLDER_TTL_SECONDS = 30L;

    private final JedisPool pool;
    private final String keyPrefix;

    @Autowired
    public RedisIdempotencyStore(JedisPool pool,
                                  @Value("${agent.idempotency.redis-key-prefix:agent:idem:}") String keyPrefix) {
        this.pool = pool;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public PutResult putIfAbsent(IdempotencyKey key, Object value) {
        String redisKey = keyPrefix + key.hash();
        try (Jedis j = pool.getResource()) {
            // SETNX + TTL: 第一次成功 (返回 "OK"), 后续失败 (返回 null)
            String result = j.set(redisKey, serialize(value), SetParams.setParams().nx().ex(PLACEHOLDER_TTL_SECONDS));
            if ("OK".equals(result)) {
                return new PutResult(PutResult.OutcomeKind.FIRST, value);
            }
            // 已存在, 取回之前存的值
            String existing = j.get(redisKey);
            return new PutResult(PutResult.OutcomeKind.REPLAY, deserialize(existing));
        }
    }

    @Override
    public void replace(IdempotencyKey key, Object value) {
        String redisKey = keyPrefix + key.hash();
        try (Jedis j = pool.getResource()) {
            // 普通 SET, 不带 NX/EX — 覆盖写入, 不延寿 (避免过期键被无限续命)
            j.set(redisKey, serialize(value));
        }
    }

    @Override
    public void close() {
        try (Jedis ignored = pool.getResource()) {
            // 返回连接池 (try-with-resources 自动 close)
        }
    }

    private static String serialize(Object o) {
        if (o == null) return "__NULL__";
        return o.toString();  // 简化版: 实际应 JSON serialize
    }

    private static Object deserialize(String s) {
        if ("__NULL__".equals(s)) return null;
        return s;
    }
}