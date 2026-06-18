package io.github.yysf1949.rag.redis.version;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.github.yysf1949.rag.redis.config.RedisProperties;
import io.github.yysf1949.rag.redis.vector.RedisIndexManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import redis.clients.jedis.UnifiedJedis;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Live integration test for {@link RedisKbVersionService} against a
 * real Redis Stack instance (the local {@code rag-redis-stack} container).
 *
 * <p>Replaces the Mockito-based {@link RedisKbVersionServiceTest} with
 * real Redis commands — validates the full storage layout (hash + set +
 * publish pointer) end-to-end.</p>
 *
 * <p>Phase 20 — Testcontainers rag-redis live (adapted for Podman: connects
 * to the already-running container at localhost:6379 instead of using
 * Testcontainers lifecycle).</p>
 *
 * <p>Run with: {@code -Dredis.live.test=true}</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisKbVersionServiceLiveTest {

    private static final String TENANT = "live-tenant";
    private static final String KB = "live-kb";

    static RedisConnection connection;
    static UnifiedJedis jedis;
    static RedisIndexManager indexManager;
    static KbVersionService service;

    @BeforeAll
    static void connect() {
        RedisProperties props = new RedisProperties(
                "127.0.0.1", 6379, null, 0,
                4, 2, 1, 2000, 5000);
        connection = new RedisConnection(props);
        connection.init();
        jedis = connection.client();
        indexManager = new RedisIndexManager(connection);
        service = new RedisKbVersionService(jedis, indexManager);
    }

    @AfterAll
    static void disconnect() {
        if (connection != null) connection.shutdown();
    }

    @BeforeEach
    void cleanKeys() {
        var keys = jedis.keys("rag:*:" + TENANT + ":*");
        if (keys != null && !keys.isEmpty()) {
            jedis.del(keys.toArray(new String[0]));
        }
    }

    // ─── 1. registerVersion + listVersions ─────────────────────────────────

    @Test
    @Order(1)
    void registerVersion_thenListVersions_returnsOne() {
        service.registerVersion(TENANT, KB, 1L,
                KbVersionMeta.Status.DRAFT, "test-source");

        List<KbVersionMeta> versions = service.listVersions(TENANT, KB);
        assertEquals(1, versions.size());
        assertEquals(1L, versions.get(0).versionId());
    }

    // ─── 2. registerVersion idempotent ─────────────────────────────────────

    @Test
    @Order(2)
    void registerVersion_idempotent_noDuplicate() {
        service.registerVersion(TENANT, KB, 2L,
                KbVersionMeta.Status.DRAFT, "src");
        service.registerVersion(TENANT, KB, 2L,
                KbVersionMeta.Status.DRAFT, "other");

        List<KbVersionMeta> versions = service.listVersions(TENANT, KB);
        long countV2 = versions.stream().filter(v -> v.versionId() == 2L).count();
        assertEquals(1, countV2, "idempotent: second register should not create duplicate");
    }

    // ─── 3. publish sets active version pointer ────────────────────────────

    @Test
    @Order(3)
    void publish_setsActiveVersionPointer() {
        service.registerVersion(TENANT, KB, 3L,
                KbVersionMeta.Status.DRAFT, "src");
        service.publish(TENANT, KB, 3L);

        long pointer = indexManager.getPublishPointer(TENANT, KB);
        assertEquals(3L, pointer);
    }

    // ─── 4. publish idempotent ─────────────────────────────────────────────

    @Test
    @Order(4)
    void publish_idempotent_sameVersion() {
        service.registerVersion(TENANT, KB, 4L,
                KbVersionMeta.Status.DRAFT, "src");
        service.publish(TENANT, KB, 4L);
        service.publish(TENANT, KB, 4L);

        assertEquals(4L, indexManager.getPublishPointer(TENANT, KB));
    }

    // ─── 5. getActiveVersion after publish ─────────────────────────────────

    @Test
    @Order(5)
    void getActiveVersion_afterPublish_returnsVersion() {
        service.registerVersion(TENANT, KB, 5L,
                KbVersionMeta.Status.DRAFT, "src");
        service.publish(TENANT, KB, 5L);

        Optional<Long> active = service.getActiveVersion(TENANT, KB);
        assertTrue(active.isPresent());
        assertEquals(5L, active.get());
    }

    // ─── 6. resolveVersion negative → active ──────────────────────────────

    @Test
    @Order(6)
    void resolveVersion_negative_returnsActive() {
        service.registerVersion(TENANT, KB, 6L,
                KbVersionMeta.Status.DRAFT, "src");
        service.publish(TENANT, KB, 6L);

        long resolved = service.resolveVersion(TENANT, KB, -1L);
        assertEquals(6L, resolved);
    }

    // ─── 7. resolveVersion nonexistent throws ──────────────────────────────

    @Test
    @Order(7)
    void resolveVersion_nonexistent_throws() {
        assertThrows(KbVersionNotFoundException.class,
                () -> service.resolveVersion(TENANT, KB, 999L));
    }

    // ─── 8. rollback ──────────────────────────────────────────────────────

    @Test
    @Order(8)
    void rollback_reactivatesOldVersion() {
        service.registerVersion(TENANT, KB, 7L,
                KbVersionMeta.Status.DRAFT, "src");
        service.registerVersion(TENANT, KB, 8L,
                KbVersionMeta.Status.DRAFT, "src");
        service.publish(TENANT, KB, 7L);
        service.publish(TENANT, KB, 8L);

        service.rollback(TENANT, KB, 7L);
        assertEquals(7L, indexManager.getPublishPointer(TENANT, KB));
    }

    // ─── 9. empty tenant returns empty list ────────────────────────────────

    @Test
    @Order(9)
    void listVersions_emptyTenant_returnsEmpty() {
        List<KbVersionMeta> versions = service.listVersions("ghost-tenant", KB);
        assertTrue(versions.isEmpty());
    }

    // ─── 10. getActiveVersion empty when never published ──────────────────

    @Test
    @Order(10)
    void getActiveVersion_emptyWhenNeverPublished() {
        Optional<Long> active = service.getActiveVersion("ghost-tenant", KB);
        assertTrue(active.isEmpty());
    }
}
