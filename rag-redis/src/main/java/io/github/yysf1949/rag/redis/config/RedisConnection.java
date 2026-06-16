package io.github.yysf1949.rag.redis.config;

import io.github.yysf1949.rag.redis.exception.RedisUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

/**
 * Redis connection holder — wraps a Jedis 5 {@link JedisPooled} which
 * <em>extends</em> {@link UnifiedJedis} and therefore exposes the RediSearch
 * module commands ({@code ftCreate}, {@code ftInfo}, {@code ftSearch},
 * {@code ftDropIndex}, …) without any wrapper boilerplate.
 *
 * <p>Design spec §12 — Redis Stack 7.4 with the RediSearch module loaded.</p>
 */
public class RedisConnection {

    private static final Logger log = LoggerFactory.getLogger(RedisConnection.class);

    private final RedisProperties properties;
    private JedisPooled client;

    public RedisConnection(RedisProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        GenericObjectPoolConfig<redis.clients.jedis.Connection> poolConfig =
                new GenericObjectPoolConfig<>();
        poolConfig.setMaxTotal(properties.maxTotal());
        poolConfig.setMaxIdle(properties.maxIdle());
        poolConfig.setMinIdle(properties.minIdle());
        poolConfig.setTestOnBorrow(true);
        poolConfig.setTestWhileIdle(true);
        poolConfig.setBlockWhenExhausted(true);
        poolConfig.setMaxWait(java.time.Duration.ofMillis(properties.maxWaitMs()));

        DefaultJedisClientConfig clientConfig = DefaultJedisClientConfig.builder()
                .timeoutMillis((int) properties.commandTimeoutMs())
                .clientName("rag-app")
                .build();

        HostAndPort hp = new HostAndPort(properties.host(), properties.port());
        this.client = new JedisPooled(poolConfig, hp, clientConfig);

        // eager health check
        try {
            String pong = client.ping();
            log.info("Redis pool ready — host={} port={} maxTotal={} ping={}",
                    properties.host(), properties.port(), properties.maxTotal(), pong);
        } catch (Exception e) {
            throw new RedisUnavailableException(
                    "Failed to ping Redis at " + properties.host() + ":" + properties.port(), e);
        }
    }

    @PreDestroy
    public void shutdown() {
        if (client != null) {
            log.info("Closing Redis pool");
            client.close();
        }
    }

    public JedisPooled client() {
        if (client == null) {
            throw new RedisUnavailableException("RedisConnection not initialized");
        }
        return client;
    }

    public RedisProperties properties() {
        return properties;
    }
}