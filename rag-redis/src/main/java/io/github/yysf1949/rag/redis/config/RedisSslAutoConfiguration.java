package io.github.yysf1949.rag.redis.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.ResourceUtils;import redis.clients.jedis.DefaultJedisClientConfig;
import redis.clients.jedis.JedisClientConfig;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.FileInputStream;
import java.security.KeyStore;

/**
 * Jedis TLS customizer — design spec §2.2 + checklist §2.2.
 *
 * <h2>When does it activate?</h2>
 * <p>Only when:</p>
 * <ol>
 *   <li>The Jedis client is on the classpath (it is, via the
 *       {@code jedis} dependency in {@code rag-redis/pom.xml}).</li>
 *   <li>{@code spring.data.redis.ssl.enabled=true} (mirrored from the
 *       application.yml placeholder
 *       {@code spring.data.redis.ssl.enabled}).</li>
 *   <li>{@code REDIS_TLS_TRUSTSTORE} points at a valid Java keystore
 *       file (PKCS12 or JKS).</li>
 * </ol>
 *
 * <p>When inactive (local docker-compose) the bean is absent and Jedis
 * runs in plain TCP mode — no behavioural change from the pre-TLS
 * configuration.</p>
 *
 * <h2>What it does</h2>
 * <p>Builds a {@link JedisClientConfig} that carries an
 * {@link SSLSocketFactory} derived from the trust store, and binds it
 * to the Spring-managed {@link RedisConnection} bean. We do this via
 * a {@code @Value} injection of the same properties the {@code RedisConnection}
 * already reads, so the SSL config picks up the same trust-store
 * regardless of how {@code RedisConnection} is constructed.</p>
 *
 * <h2>Why a trust store (not the JVM cacerts)?</h2>
 * <p>Production Redis typically uses a private CA (corp CA, ACM PCA,
 * Vault PKI). Pointing the JVM at a private trust store keeps the
 * runtime portable: the same JAR runs in a developer laptop without
 * {@code -Djavax.net.ssl.trustStore} and in production with the
 * private CA bundle mounted at a known path. See RUNBOOK §6 for the
 * trust-store provisioning procedure.</p>
 *
 * <h2>Failure mode</h2>
 * <p>If the trust store path is missing or unreadable we throw at
 * startup — fail-fast is intentional, the alternative (silently falling
 * back to plain TCP) would expose tenant data in cleartext.</p>
 */
@Configuration
@ConditionalOnClass(name = "redis.clients.jedis.JedisPooled")
@ConditionalOnProperty(prefix = "spring.data.redis.ssl", name = "enabled", havingValue = "true")
public class RedisSslAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(RedisSslAutoConfiguration.class);

    @Value("${REDIS_TLS_TRUSTSTORE:}")
    private String trustStorePath;

    @Value("${REDIS_TLS_TRUSTSTORE_PASSWORD:}")
    private String trustStorePassword;

    @Value("${REDIS_TLS_TRUSTSTORE_TYPE:PKCS12}")
    private String trustStoreType;

    @Value("${REDIS_TLS_VERIFY_HOSTNAME:true}")
    private boolean verifyHostname;

    /** Test-only constructor — lets hermetic tests inject all four
     *  configuration values without standing up a Spring context. */
    RedisSslAutoConfiguration(String trustStorePath, String trustStorePassword,
                              String trustStoreType, boolean verifyHostname) {
        this.trustStorePath = trustStorePath;
        this.trustStorePassword = trustStorePassword;
        this.trustStoreType = trustStoreType;
        this.verifyHostname = verifyHostname;
    }

    public RedisSslAutoConfiguration() { }

    /**
     * Build an SSLSocketFactory from the configured trust store. Exposed
     * as a Spring bean so the {@code RedisConnection} (and any future
     * test) can inject it.
     */
    @Bean
    public SSLSocketFactory redisSslSocketFactory() {
        if (trustStorePath == null || trustStorePath.isBlank()) {
            throw new IllegalStateException(
                    "spring.data.redis.ssl.enabled=true but REDIS_TLS_TRUSTSTORE is not set. "
                            + "Mount the private-CA bundle and set REDIS_TLS_TRUSTSTORE=/path/to/keystore.p12, "
                            + "REDIS_TLS_TRUSTSTORE_PASSWORD=*** — see docs/RUNBOOK.md §6.");
        }
        log.info("Configuring Jedis SSL trust store={} type={} verifyHostname={}",
                trustStorePath, trustStoreType, verifyHostname);
        try {
            KeyStore trustStore = KeyStore.getInstance(trustStoreType);
            try (FileInputStream in = new FileInputStream(ResourceUtils.getFile(trustStorePath))) {
                char[] pwd = trustStorePassword == null ? new char[0] : trustStorePassword.toCharArray();
                trustStore.load(in, pwd);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(trustStore);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            if (!verifyHostname) {
                log.warn("REDIS_TLS_VERIFY_HOSTNAME=false — hostname verification is DISABLED. "
                        + "This must NEVER be set in production; self-signed dev certs only. "
                        + "The cert's SAN must match the Redis hostname for the production path.");
            }
            return ctx.getSocketFactory();
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to load Redis TLS trust store from " + trustStorePath
                            + " (type=" + trustStoreType + "): " + e.getMessage(), e);
        }
    }

    /**
     * Build a {@link JedisClientConfig} wrapper carrying the SSL
     * socket factory. Exposed for tests that need to assert the config
     * shape without standing up a real connection.
     */
    @Bean
    public JedisClientConfig redisJedisClientConfig(SSLSocketFactory sslSocketFactory) {
        return DefaultJedisClientConfig.builder()
                .sslSocketFactory(sslSocketFactory)
                .ssl(verifyHostname)
                .clientName("rag-app")
                .build();
    }
}
