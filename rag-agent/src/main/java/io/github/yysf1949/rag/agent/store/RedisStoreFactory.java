package io.github.yysf1949.rag.agent.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import redis.clients.jedis.JedisPooled;

/**
 * Redis 持久化工厂 — 提供 JSON 序列化 + Key 生成 + Jedis 客户端。
 *
 * <p>Phase 11 所有 RedisRepository 用同一个 factory 实例（单例注入）。
 * 不自己 new JedisPooled, 而是收到已有的 {@code JedisPooled} bean
 * （由 rag-redis RedisConnection 提供）。</p>
 *
 * <h2>Key 格式</h2>
 * {@code agent:<entity>:<tenantId>:<id>}
 * <br>无 tenantId 的实体: {@code agent:<entity>:<id>}
 *
 * <h2>线程安全</h2>
 * JedisPooled 本身是线程安全的, factory 无状态。
 */
public class RedisStoreFactory {

    private final JedisPooled jedis;
    private final ObjectMapper mapper;
    private final String keyPrefix;

    public RedisStoreFactory(JedisPooled jedis, ObjectMapper mapper) {
        this(jedis, mapper, "agent:");
    }

    public RedisStoreFactory(JedisPooled jedis, ObjectMapper mapper, String keyPrefix) {
        this.jedis = jedis;
        this.mapper = mapper;
        this.keyPrefix = keyPrefix;
    }

    public JedisPooled jedis() { return jedis; }
    public ObjectMapper mapper() { return mapper; }

    /** agent:order:t1:ORD-1 */
    public String key(String entity, String tenantId, String id) {
        return keyPrefix + entity + ":" + tenantId + ":" + id;
    }

    /** agent:ticket:TKT-abc123 */
    public String key(String entity, String id) {
        return keyPrefix + entity + ":" + id;
    }

    /** agent:order:t1 (列出 tenant 所有 order) */
    public String tenantPrefix(String entity, String tenantId) {
        return keyPrefix + entity + ":" + tenantId + ":";
    }

    /** agent:ticket: (全部) */
    public String entityPrefix(String entity) {
        return keyPrefix + entity + ":";
    }
}