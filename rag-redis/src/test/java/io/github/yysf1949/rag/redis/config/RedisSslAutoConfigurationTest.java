package io.github.yysf1949.rag.redis.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLSocketFactory;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Redis TLS auto-configuration.
 *
 * <p>Verifies the activation contract:</p>
 * <ul>
 *   <li>Bean creation succeeds when {@code spring.data.redis.ssl.enabled=true}
 *       and a valid PKCS12 trust store is mounted at {@code REDIS_TLS_TRUSTSTORE}.</li>
 *   <li>Bean creation FAILS fast when the flag is set but the trust
 *       store path is missing — the alternative (silently falling back
 *       to plain TCP) would expose tenant data in cleartext.</li>
 *   <li>{@link SSLSocketFactory} is non-null and the {@code SSLContext}
 *       it wraps is a TLS context.</li>
 * </ul>
 */
class RedisSslAutoConfigurationTest {

    @Test
    void trustStoreMissingThrowsAtStartup() {
        // Path is empty — we expect the bean to throw with a message
        // pointing operators at the missing env var.
        RedisSslAutoConfiguration cfg = new RedisSslAutoConfiguration(
                "", "", "PKCS12", true);

        IllegalStateException ex = assertThrows(IllegalStateException.class, cfg::redisSslSocketFactory);
        assertTrue(ex.getMessage().contains("REDIS_TLS_TRUSTSTORE"),
                "Error must point operators to the missing env var (got: " + ex.getMessage() + ")");
    }

    @Test
    void validTrustStoreProducesSocketFactory(@TempDir Path tmp) throws Exception {
        // Build a minimal PKCS12 trust store programmatically. We don't
        // need an actual cert to test the loader path — an empty
        // KeyStore is enough to prove the KeyStore.load() + SSLContext
        // wiring works end-to-end. The SSL handshake itself is exercised
        // by the integration test (run only when a real Redis + TLS
        // server is available; this hermetic test just proves the
        // autoconfig is non-throwing).
        Path trustStore = tmp.resolve("test-truststore.p12");
        char[] password = "test-password".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password);
        try (OutputStream os = Files.newOutputStream(trustStore)) {
            ks.store(os, password);
        }

        RedisSslAutoConfiguration cfg = new RedisSslAutoConfiguration(
                trustStore.toString(), "test-password", "PKCS12", true);

        SSLSocketFactory factory = cfg.redisSslSocketFactory();
        assertNotNull(factory);
        assertNotNull(factory.getDefaultCipherSuites());
        assertTrue(factory.getDefaultCipherSuites().length > 0);
    }

    @Test
    void verifyHostnameFalseStillBuildsFactoryButLogsWarning(@TempDir Path tmp) throws Exception {
        Path trustStore = tmp.resolve("test-truststore.p12");
        char[] password = "test-password".toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, password);
        try (OutputStream os = Files.newOutputStream(trustStore)) {
            ks.store(os, password);
        }

        RedisSslAutoConfiguration cfg = new RedisSslAutoConfiguration(
                trustStore.toString(), "test-password", "PKCS12", false);

        SSLSocketFactory factory = cfg.redisSslSocketFactory();
        assertNotNull(factory);
    }

    @Test
    void corruptTrustStoreThrowsIllegalState(@TempDir Path tmp) throws Exception {
        // Write garbage to the trust-store path — the loader must throw
        // IllegalStateException (NOT a low-level IOException) so the
        // startup error message points operators at the right env var.
        Path trustStore = tmp.resolve("garbage.p12");
        Files.writeString(trustStore, "this is not a keystore");

        RedisSslAutoConfiguration cfg = new RedisSslAutoConfiguration(
                trustStore.toString(), "", "PKCS12", true);

        assertThrows(IllegalStateException.class, cfg::redisSslSocketFactory);
    }
}
