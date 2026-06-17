package io.github.yysf1949.rag.redis.config;

import io.github.yysf1949.rag.redis.exception.RedisUnavailableException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.apache.commons.pool2.impl.GenericObjectPoolConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.HostAndPort;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

import javax.net.ssl.SSLSocketFactory;

/**
 * Redis connection holder — wraps a Jedis 5 {@link JedisPooled} which
 * <em>extends</em> {@link UnifiedJedis} and therefore exposes the RediSearch
 * module commands ({@code ftCreate}, {@code ftInfo}, {@code ftSearch},
 * {@code ftDropIndex}, …) without any wrapper boilerplate.
 *
 * <p>Design spec §12 — Redis Stack 7.4 with the RediSearch module loaded.</p>
 *
 * <h2>TLS support</h2>
 * <p>When {@code spring.data.redis.ssl.enabled=true}, Spring will
 * autowire the {@link SSLSocketFactory} bean from
 * {@link RedisSslAutoConfiguration} and we install it on the Jedis
 * client config. When TLS is disabled (default — local
 * docker-compose) the constructor receives {@code null} and the
 * client runs in plain TCP mode. The {@code @Autowired(required = false)}
 * keeps the no-TLS path completely free of SSL machinery.</p>
 */
public class RedisConnection {

    private static final Logger log = LoggerFactory.getLogger(RedisConnection.class);

    private final RedisProperties properties;
    private final SSLSocketFactory sslSocketFactory;
    private JedisPooled client;

    public RedisConnection(RedisProperties properties) {
        this(properties, null);
    }

    public RedisConnection(RedisProperties properties, @Nullable SSLSocketFactory sslSocketFactory) {
        this.properties = properties;
        this.sslSocketFactory = sslSocketFactory;
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

        DefaultJedisClientConfig.Builder cfgBuilder = DefaultJedisClientConfig.builder()
                .timeoutMillis((int) properties.commandTimeoutMs())
                .clientName("rag-app");
        if (sslSocketFactory != null) {
            cfgBuilder.sslSocketFactory(sslSocketFactory).ssl(true);
            log.info("Redis pool starting in TLS mode");
        }

        HostAndPort hp = new HostAndPort(properties.host(), properties.port());
        this.client = new JedisPooled(poolConfig, hp, cfgBuilder.build());

        // eager health check
        try {
            String pong = client.ping();
            log.info("Redis pool ready — host={} port={} maxTotal={} ping={}{}",
                    properties.host(), properties.port(), properties.maxTotal(), pong,
                    sslSocketFactory != null ? " TLS=on" : "");
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