package io.github.yysf1949.rag.redis.version;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.github.yysf1949.rag.redis.config.RedisProperties;
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
 * Live integration test for {@link RedisDocumentVersionService} against a
 * real Redis Stack instance (the local {@code rag-redis-stack} container).
 *
 * <p>Replaces the Mockito-based {@link RedisDocumentVersionServiceTest} with
 * real Redis commands — validates the full storage layout (hash + ZSET +
 * active pointer) end-to-end.</p>
 *
 * <p>Phase 20 — Testcontainers rag-redis live (adapted for Podman: connects
 * to the already-running container at localhost:6379 instead of using
 * Testcontainers lifecycle).</p>
 *
 * <p>Run with: {@code -Dredis.live.test=true}</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisDocumentVersionServiceLiveTest {

    private static final String TENANT = "live-tenant";
    private static final String KB = "live-kb";
    private static final String DOC = "live-doc";

    static RedisConnection connection;
    static UnifiedJedis jedis;
    static DocumentVersionService service;

    @BeforeAll
    static void connect() {
        RedisProperties props = new RedisProperties(
                "127.0.0.1", 6379, null, 0,
                4, 2, 1, 2000, 5000);
        connection = new RedisConnection(props);
        connection.init();
        jedis = connection.client();
        service = new RedisDocumentVersionService(jedis);
    }

    @AfterAll
    static void disconnect() {
        if (connection != null) connection.shutdown();
    }

    @BeforeEach
    void cleanKeys() {
        var keys = jedis.keys("rag:kb-doc-*:" + TENANT + ":*");
        if (keys != null && !keys.isEmpty()) {
            jedis.del(keys.toArray(new String[0]));
        }
    }

    // ─── 1. registerVersion + listVersions ─────────────────────────────────

    @Test
    @Order(1)
    void registerVersion_thenListVersions_returnsOne() {
        DocumentVersionMeta meta = service.registerVersion(
                TENANT, KB, DOC, 1L,
                DocumentVersionMeta.Status.DRAFT, "test-source", 5);

        assertEquals(1L, meta.versionId());
        assertEquals(DocumentVersionMeta.Status.DRAFT, meta.status());
        assertEquals(5, meta.chunkCount());

        List<DocumentVersionMeta> versions = service.listVersions(TENANT, KB, DOC);
        assertEquals(1, versions.size());
        assertEquals(1L, versions.get(0).versionId());
    }

    // ─── 2. registerVersion idempotent ─────────────────────────────────────

    @Test
    @Order(2)
    void registerVersion_idempotent_noDuplicate() {
        service.registerVersion(TENANT, KB, DOC, 2L,
                DocumentVersionMeta.Status.DRAFT, "src", 3);
        service.registerVersion(TENANT, KB, DOC, 2L,
                DocumentVersionMeta.Status.DRAFT, "other-src", 10);

        List<DocumentVersionMeta> versions = service.listVersions(TENANT, KB, DOC);
        long countV2 = versions.stream().filter(v -> v.versionId() == 2L).count();
        assertEquals(1, countV2, "idempotent: second register should not create a duplicate");
    }

    // ─── 3. publish sets ACTIVE ────────────────────────────────────────────

    @Test
    @Order(3)
    void publish_setsActiveVersion() {
        service.registerVersion(TENANT, KB, DOC, 3L,
                DocumentVersionMeta.Status.DRAFT, "src", 4);
        DocumentVersionMeta published = service.publish(TENANT, KB, DOC, 3L, "release");

        assertEquals(DocumentVersionMeta.Status.ACTIVE, published.status());
        assertNotNull(published.publishedAt());

        Optional<Long> active = service.getActiveVersion(TENANT, KB, DOC);
        assertTrue(active.isPresent());
        assertEquals(3L, active.get());
    }

    // ─── 4. publish deprecates previous active ────────────────────────────

    @Test
    @Order(4)
    void publish_deprecatesPreviousActive() {
        service.registerVersion(TENANT, KB, DOC, 4L,
                DocumentVersionMeta.Status.DRAFT, "src", 2);
        service.registerVersion(TENANT, KB, DOC, 5L,
                DocumentVersionMeta.Status.DRAFT, "src", 3);
        service.publish(TENANT, KB, DOC, 4L, null);
        service.publish(TENANT, KB, DOC, 5L, null);

        List<DocumentVersionMeta> versions = service.listVersions(TENANT, KB, DOC);
        Optional<DocumentVersionMeta> v4 = versions.stream()
                .filter(v -> v.versionId() == 4L).findFirst();
        assertTrue(v4.isPresent());
        assertEquals(DocumentVersionMeta.Status.DEPRECATED, v4.get().status());

        assertEquals(5L, service.getActiveVersion(TENANT, KB, DOC).orElse(-1L));
    }

    // ─── 5. publish idempotent ─────────────────────────────────────────────

    @Test
    @Order(5)
    void publish_idempotent_sameVersion() {
        service.registerVersion(TENANT, KB, DOC, 6L,
                DocumentVersionMeta.Status.DRAFT, "src", 1);
        service.publish(TENANT, KB, DOC, 6L, "first");
        DocumentVersionMeta again = service.publish(TENANT, KB, DOC, 6L, "second");

        assertEquals(DocumentVersionMeta.Status.ACTIVE, again.status());
        assertEquals("second", again.sourceLabel());
    }

    // ─── 6. rollback ──────────────────────────────────────────────────────

    @Test
    @Order(6)
    void rollback_reactivatesOldVersion() {
        service.registerVersion(TENANT, KB, DOC, 7L,
                DocumentVersionMeta.Status.DRAFT, "src", 2);
        service.registerVersion(TENANT, KB, DOC, 8L,
                DocumentVersionMeta.Status.DRAFT, "src", 3);
        service.publish(TENANT, KB, DOC, 7L, null);
        service.publish(TENANT, KB, DOC, 8L, null);

        DocumentVersionMeta rolled = service.rollback(TENANT, KB, DOC, 7L);
        assertEquals(DocumentVersionMeta.Status.ACTIVE, rolled.status());
        assertEquals(7L, service.getActiveVersion(TENANT, KB, DOC).orElse(-1L));
    }

    // ─── 7. rollback nonexistent version throws ───────────────────────────

    @Test
    @Order(7)
    void rollback_nonexistentVersion_throws() {
        assertThrows(DocumentVersionNotFoundException.class,
                () -> service.rollback(TENANT, KB, DOC, 999L));
    }

    // ─── 8. resolveVersion negative → active ──────────────────────────────

    @Test
    @Order(8)
    void resolveVersion_negative_returnsActive() {
        service.registerVersion(TENANT, KB, DOC, 9L,
                DocumentVersionMeta.Status.DRAFT, "src", 2);
        service.publish(TENANT, KB, DOC, 9L, null);

        long resolved = service.resolveVersion(TENANT, KB, DOC, -1L);
        assertEquals(9L, resolved);
    }

    // ─── 9. resolveVersion positive existing → pass-through ───────────────

    @Test
    @Order(9)
    void resolveVersion_positiveExisting_passThrough() {
        service.registerVersion(TENANT, KB, DOC, 10L,
                DocumentVersionMeta.Status.DRAFT, "src", 1);

        long resolved = service.resolveVersion(TENANT, KB, DOC, 10L);
        assertEquals(10L, resolved);
    }

    // ─── 10. resolveVersion nonexistent throws ────────────────────────────

    @Test
    @Order(10)
    void resolveVersion_nonexistent_throws() {
        assertThrows(DocumentVersionNotFoundException.class,
                () -> service.resolveVersion(TENANT, KB, DOC, 777L));
    }

    // ─── 11. empty tenant returns empty list ───────────────────────────────

    @Test
    @Order(11)
    void listVersions_emptyTenant_returnsEmpty() {
        List<DocumentVersionMeta> versions = service.listVersions("ghost-tenant", KB, DOC);
        assertTrue(versions.isEmpty());
    }

    // ─── 12. getActiveVersion empty when never published ──────────────────

    @Test
    @Order(12)
    void getActiveVersion_emptyWhenNeverPublished() {
        Optional<Long> active = service.getActiveVersion("ghost-tenant", KB, DOC);
        assertTrue(active.isEmpty());
    }
}
