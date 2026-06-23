package io.github.yysf1949.rag.redis.version;

import io.github.yysf1949.rag.core.exception.DocumentVersionNotFoundException;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests {@link RedisDocumentVersionService} using a Mockito {@link UnifiedJedis} stub.
 *
 * <p>jedis 5.2.0 {@code hset} has two real overloads:
 * <ul>
 *   <li>{@code hset(String, String, String)} — single field (3-arg)</li>
 *   <li>{@code hset(String, Map<String,String>)} — multi-field via Map</li>
 * </ul>
 * The production code uses both; tests follow.</p>
 *
 * <p>Keys differ from P2 ({@code RedisKbVersionService}) by carrying the
 * {@code docId} dimension:
 * <ul>
 *   <li>{@code rag:kb-doc-version-meta:{t}:{k}:{d}:{v}} (hash)</li>
 *   <li>{@code rag:kb-doc-versions:{t}:{k}:{d}} (ZSET scored by versionId)</li>
 *   <li>{@code rag:kb-doc-active:{t}:{k}:{d}} (string pointer)</li>
 * </ul>
 * Compared to P2's SET-per-KB, this version uses a ZSET for natural
 * newest-first ordering via {@code zrevrange}.</p>
 */
class RedisDocumentVersionServiceTest {

    private UnifiedJedis jedis;
    private DocumentVersionService service;

    // Test fixture dimensions
    private static final String T = "t1";
    private static final String K = "kb1";
    private static final String D = "doc1";

    @BeforeEach
    void setUp() {
        jedis = mock(UnifiedJedis.class);
        service = new RedisDocumentVersionService(jedis);
    }

    private static String metaKey(String t, String k, String d, long v) {
        return "rag:kb-doc-version-meta:" + t + ":" + k + ":" + d + ":" + v;
    }

    private static String versionsKey(String t, String k, String d) {
        return "rag:kb-doc-versions:" + t + ":" + k + ":" + d;
    }

    private static String activeKey(String t, String k, String d) {
        return "rag:kb-doc-active:" + t + ":" + k + ":" + d;
    }

    // ────────────────────────────────────────────────────────────────────
    // 1. listVersions — empty returns empty list
    // ────────────────────────────────────────────────────────────────────
    @Test
    void listVersions_empty_returns_empty_list() {
        when(jedis.zrevrange(versionsKey(T, K, D), 0, -1))
                .thenReturn(List.of());

        List<DocumentVersionMeta> all = service.listVersions(T, K, D);

        assertTrue(all.isEmpty());
    }

    // ────────────────────────────────────────────────────────────────────
    // 2. listVersions — ZSET orders newest first (zrevrange ordering)
    // ────────────────────────────────────────────────────────────────────
    @Test
    void listVersions_zset_orders_newest_first() {
        // zrevrange returns already-ordered descending; mock the production's call.
        when(jedis.zrevrange(versionsKey(T, K, D), 0, -1))
                .thenReturn(List.of("3", "2", "1"));
        when(jedis.exists(metaKey(T, K, D, 3L))).thenReturn(true);
        when(jedis.exists(metaKey(T, K, D, 2L))).thenReturn(true);
        when(jedis.exists(metaKey(T, K, D, 1L))).thenReturn(true);
        when(jedis.hgetAll(metaKey(T, K, D, 3L))).thenReturn(Map.of(
                "status", "ACTIVE",
                "createdAt", "2026-03-01T00:00:00Z",
                "publishedAt", "2026-03-02T00:00:00Z",
                "chunkCount", "30"));
        when(jedis.hgetAll(metaKey(T, K, D, 2L))).thenReturn(Map.of(
                "status", "DEPRECATED",
                "createdAt", "2026-02-01T00:00:00Z",
                "chunkCount", "20"));
        when(jedis.hgetAll(metaKey(T, K, D, 1L))).thenReturn(Map.of(
                "status", "DEPRECATED",
                "createdAt", "2026-01-01T00:00:00Z",
                "chunkCount", "10"));

        List<DocumentVersionMeta> all = service.listVersions(T, K, D);

        assertEquals(3, all.size());
        assertEquals(3L, all.get(0).versionId());
        assertEquals(2L, all.get(1).versionId());
        assertEquals(1L, all.get(2).versionId());
        assertEquals(DocumentVersionMeta.Status.ACTIVE, all.get(0).status());
        assertEquals(30, all.get(0).chunkCount());
        assertNotNull(all.get(0).publishedAt());
    }

    // ────────────────────────────────────────────────────────────────────
    // 3. getActiveVersion — empty when no pointer
    // ────────────────────────────────────────────────────────────────────
    @Test
    void getActiveVersion_empty_when_no_pointer() {
        when(jedis.get(activeKey(T, K, D))).thenReturn(null);
        assertTrue(service.getActiveVersion(T, K, D).isEmpty());
    }

    // ────────────────────────────────────────────────────────────────────
    // 4. getActiveVersion — returns pointer value
    // ────────────────────────────────────────────────────────────────────
    @Test
    void getActiveVersion_returns_pointer_value() {
        when(jedis.get(activeKey(T, K, D))).thenReturn("7");
        assertEquals(7L, service.getActiveVersion(T, K, D).orElseThrow());
    }

    // ────────────────────────────────────────────────────────────────────
    // 5. getActiveVersion — corrupt value returns empty
    // ────────────────────────────────────────────────────────────────────
    @Test
    void getActiveVersion_corrupt_value_returns_empty() {
        when(jedis.get(activeKey(T, K, D))).thenReturn("not-a-number");
        assertTrue(service.getActiveVersion(T, K, D).isEmpty());
    }

    // ────────────────────────────────────────────────────────────────────
    // 6. publish — makes target ACTIVE, old becomes DEPRECATED
    // ────────────────────────────────────────────────────────────────────
    @Test
    void publish_makes_target_active_and_old_deprecated() {
        // Active pointer currently points to v=1.
        when(jedis.get(activeKey(T, K, D))).thenReturn("1");
        // Both meta keys exist (so ensureMetaExists early-returns on v=2).
        when(jedis.exists(metaKey(T, K, D, 1L))).thenReturn(true);
        when(jedis.exists(metaKey(T, K, D, 2L))).thenReturn(true);
        // publish() returns by reading the final meta — stub hgetAll for v=2.
        when(jedis.hgetAll(metaKey(T, K, D, 2L))).thenReturn(Map.of(
                "status", "ACTIVE",
                "createdAt", "2026-02-01T00:00:00Z",
                "publishedAt", "2026-02-02T00:00:00Z",
                "chunkCount", "10"));

        service.publish(T, K, D, 2L, null);

        // v=1 → DEPRECATED via setStatus (3-arg hset).
        verify(jedis).hset(eq(metaKey(T, K, D, 1L)), eq("status"), eq("DEPRECATED"));
        // v=2 → ACTIVE + publishedAt (Map hset).
        verify(jedis).hset(eq(metaKey(T, K, D, 2L)), argThatMap(m ->
                "ACTIVE".equals(m.get("status")) && m.containsKey("publishedAt")));
        // Pointer updated to "2".
        verify(jedis).set(eq(activeKey(T, K, D)), eq("2"));
        // ZSET membership ensured for v=2.
        verify(jedis).zadd(eq(versionsKey(T, K, D)), eq(2.0), eq("2"));
    }

    // ────────────────────────────────────────────────────────────────────
    // 7. publish — idempotent when same version; updates publishedAt
    // ────────────────────────────────────────────────────────────────────
    @Test
    void publish_same_version_idempotent_updates_publishedAt() {
        when(jedis.get(activeKey(T, K, D))).thenReturn("3");
        when(jedis.exists(metaKey(T, K, D, 3L))).thenReturn(true);
        // publish() returns via readMeta → need hgetAll for v=3.
        when(jedis.hgetAll(metaKey(T, K, D, 3L))).thenReturn(Map.of(
                "status", "ACTIVE",
                "createdAt", "2026-01-01T00:00:00Z",
                "publishedAt", "2026-01-02T00:00:00Z",
                "chunkCount", "5"));

        service.publish(T, K, D, 3L, "label-x");

        // The idempotent branch writes publishedAt (+ sourceLabel) via Map hset.
        verify(jedis).hset(eq(metaKey(T, K, D, 3L)), argThatMap(m ->
                m.containsKey("publishedAt") && "label-x".equals(m.get("sourceLabel"))));
        // No 3-arg status hset on the active meta (we do not change status).
        verify(jedis, never()).hset(eq(metaKey(T, K, D, 3L)),
                eq("status"), anyString());
        // Pointer untouched.
        verify(jedis, never()).set(eq(activeKey(T, K, D)), anyString());
        // ZSET membership ensured.
        verify(jedis).zadd(eq(versionsKey(T, K, D)), eq(3.0), eq("3"));
    }

    // ────────────────────────────────────────────────────────────────────
    // 8. publish — first time (no previous active) creates initial meta
    // ────────────────────────────────────────────────────────────────────
    @Test
    void publish_first_time_creates_initial_meta() {
        when(jedis.get(activeKey(T, K, D))).thenReturn(null);
        // ensureMetaExists sees v=4 missing → creates.
        // After creation, the subsequent readMeta(v=4) → exists must return true.
        when(jedis.exists(metaKey(T, K, D, 4L)))
                .thenReturn(false)   // ensureMetaExists pre-check
                .thenReturn(true);  // readMeta post-publish check
        // publish() returns via readMeta → need hgetAll for v=4.
        when(jedis.hgetAll(metaKey(T, K, D, 4L))).thenReturn(Map.of(
                "status", "ACTIVE",
                "createdAt", "2026-01-01T00:00:00Z",
                "publishedAt", "2026-01-02T00:00:00Z",
                "chunkCount", "0"));

        service.publish(T, K, D, 4L, "first-drop");

        // ensureMetaExists writes initial DRAFT meta via Map hset.
        verify(jedis).hset(eq(metaKey(T, K, D, 4L)), argThatMap(m ->
                "DRAFT".equals(m.get("status"))
                        && m.containsKey("createdAt")
                        && "0".equals(m.get("chunkCount"))));
        // Then publish promotes to ACTIVE.
        verify(jedis).hset(eq(metaKey(T, K, D, 4L)), argThatMap(m ->
                "ACTIVE".equals(m.get("status")) && m.containsKey("publishedAt")));
        // Pointer written.
        verify(jedis).set(eq(activeKey(T, K, D)), eq("4"));
    }

    // ────────────────────────────────────────────────────────────────────
    // 9. rollback — to an old version sets active
    // ────────────────────────────────────────────────────────────────────
    @Test
    void rollback_to_old_version_sets_active() {
        // v=2 currently active; we roll back to v=1.
        when(jedis.get(activeKey(T, K, D))).thenReturn("2");
        when(jedis.exists(metaKey(T, K, D, 1L))).thenReturn(true);
        when(jedis.exists(metaKey(T, K, D, 2L))).thenReturn(true);
        // rollback first calls readMeta(v=1) → exists check + hgetAll.
        when(jedis.hgetAll(metaKey(T, K, D, 1L))).thenReturn(Map.of(
                "status", "DEPRECATED",
                "createdAt", "2026-01-01T00:00:00Z",
                "chunkCount", "5"));
        // then publish(v=1) returns via readMeta → hgetAll again on v=1.
        // The same stub above already covers it (mock returns same Map).
        // But also readMeta(v=2) is called to check existence only — exists stub covers.

        service.rollback(T, K, D, 1L);

        // Pointer must end up pointing at v=1.
        verify(jedis).set(eq(activeKey(T, K, D)), eq("1"));
        // v=2 becomes DEPRECATED.
        verify(jedis).hset(eq(metaKey(T, K, D, 2L)), eq("status"), eq("DEPRECATED"));
        // v=1 promoted to ACTIVE.
        verify(jedis).hset(eq(metaKey(T, K, D, 1L)), argThatMap(m ->
                "ACTIVE".equals(m.get("status"))));
    }

    // ────────────────────────────────────────────────────────────────────
    // 10. rollback — to unknown version throws NotFound
    // ────────────────────────────────────────────────────────────────────
    @Test
    void rollback_to_unknown_version_throws_NotFoundException() {
        when(jedis.exists(metaKey(T, K, D, 99L))).thenReturn(false);
        assertThrows(DocumentVersionNotFoundException.class,
                () -> service.rollback(T, K, D, 99L));
        // Pointer must not be touched.
        verify(jedis, never()).set(eq(activeKey(T, K, D)), anyString());
    }

    // ────────────────────────────────────────────────────────────────────
    // 11. resolveVersion — -1 resolves to active
    // ────────────────────────────────────────────────────────────────────
    @Test
    void resolveVersion_minus_one_resolves_to_active() {
        when(jedis.get(activeKey(T, K, D))).thenReturn("11");
        assertEquals(11L, service.resolveVersion(T, K, D, -1L));
    }

    // ────────────────────────────────────────────────────────────────────
    // 12. resolveVersion — -1 with no active throws NotFound
    // ────────────────────────────────────────────────────────────────────
    @Test
    void resolveVersion_minus_one_when_no_active_throws_NotFound() {
        when(jedis.get(activeKey(T, K, D))).thenReturn(null);
        assertThrows(DocumentVersionNotFoundException.class,
                () -> service.resolveVersion(T, K, D, -1L));
    }

    // ────────────────────────────────────────────────────────────────────
    // 13. resolveVersion — positive returns same when meta exists
    // ────────────────────────────────────────────────────────────────────
    @Test
    void resolveVersion_positive_returns_same_when_meta_exists() {
        when(jedis.exists(metaKey(T, K, D, 5L))).thenReturn(true);
        when(jedis.hgetAll(metaKey(T, K, D, 5L))).thenReturn(Map.of(
                "status", "ACTIVE",
                "createdAt", "2026-01-01T00:00:00Z"));
        assertEquals(5L, service.resolveVersion(T, K, D, 5L));
    }

    // ────────────────────────────────────────────────────────────────────
    // 14. resolveVersion — positive unknown throws NotFound
    // ────────────────────────────────────────────────────────────────────
    @Test
    void resolveVersion_positive_unknown_throws_NotFound() {
        when(jedis.exists(metaKey(T, K, D, 5L))).thenReturn(false);
        assertThrows(DocumentVersionNotFoundException.class,
                () -> service.resolveVersion(T, K, D, 5L));
    }

    // ────────────────────────────────────────────────────────────────────
    // 15. registerVersion — creates meta and adds to ZSET
    // ────────────────────────────────────────────────────────────────────
    @Test
    void registerVersion_creates_meta_and_adds_to_zset() {
        when(jedis.exists(metaKey(T, K, D, 1L))).thenReturn(false);

        service.registerVersion(T, K, D, 1L,
                DocumentVersionMeta.Status.DRAFT, "src", 7);

        // Initial Map hset with status/createdAt/chunkCount/sourceLabel.
        verify(jedis).hset(eq(metaKey(T, K, D, 1L)), argThatMap(m ->
                "DRAFT".equals(m.get("status"))
                        && m.containsKey("createdAt")
                        && "7".equals(m.get("chunkCount"))
                        && "src".equals(m.get("sourceLabel"))));
        // ZSET scored by versionId.
        verify(jedis).zadd(eq(versionsKey(T, K, D)), eq(1.0), eq("1"));
        // Active pointer is NOT touched by registerVersion.
        verify(jedis, never()).set(eq(activeKey(T, K, D)), anyString());
    }

    // ────────────────────────────────────────────────────────────────────
    // 16. registerVersion — idempotent; second register is no-op for meta
    // ────────────────────────────────────────────────────────────────────
    @Test
    void registerVersion_idempotent_second_register_noop() {
        // Meta already exists → first-wins idempotency.
        when(jedis.exists(metaKey(T, K, D, 1L))).thenReturn(true);
        // readMeta(v=1) inside the idempotent branch needs hgetAll.
        when(jedis.hgetAll(metaKey(T, K, D, 1L))).thenReturn(Map.of(
                "status", "DRAFT",
                "createdAt", "2026-01-01T00:00:00Z",
                "chunkCount", "7"));

        DocumentVersionMeta result = service.registerVersion(T, K, D, 1L,
                DocumentVersionMeta.Status.ACTIVE, "ignored", 99);

        // No Map hset (the initial creation is skipped).
        verify(jedis, never()).hset(eq(metaKey(T, K, D, 1L)), any(Map.class));
        // 3-arg hset also not called from registerVersion.
        verify(jedis, never()).hset(eq(metaKey(T, K, D, 1L)),
                anyString(), anyString());
        // ZSET membership still ensured (idempotent zadd).
        verify(jedis).zadd(eq(versionsKey(T, K, D)), eq(1.0), eq("1"));
        // Returns the existing meta via readMeta.
        assertEquals(1L, result.versionId());
    }

    // ────────────────────────────────────────────────────────────────────
    // 17. publish — persists sourceLabel on first publish
    // ────────────────────────────────────────────────────────────────────
    @Test
    void publish_persists_sourceLabel() {
        when(jedis.get(activeKey(T, K, D))).thenReturn(null);
        // Same two-call exists pattern: ensureMetaExists pre-check, readMeta post-check.
        when(jedis.exists(metaKey(T, K, D, 6L)))
                .thenReturn(false)
                .thenReturn(true);
        // publish() returns via readMeta → need hgetAll for v=6.
        when(jedis.hgetAll(metaKey(T, K, D, 6L))).thenReturn(Map.of(
                "status", "ACTIVE",
                "createdAt", "2026-01-01T00:00:00Z",
                "publishedAt", "2026-01-02T00:00:00Z",
                "chunkCount", "0",
                "sourceLabel", "Q2-doc-drop"));

        service.publish(T, K, D, 6L, "Q2-doc-drop");

        // The final promotion hset must include sourceLabel.
        verify(jedis).hset(eq(metaKey(T, K, D, 6L)), argThatMap(m ->
                "ACTIVE".equals(m.get("status"))
                        && m.containsKey("publishedAt")
                        && "Q2-doc-drop".equals(m.get("sourceLabel"))));
    }

    /** Match a Map<String,String> argument with the supplied predicate. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> argThatMap(java.util.function.Predicate<Map<String, String>> p) {
        return org.mockito.ArgumentMatchers.argThat(m -> m != null && p.test((Map<String, String>) m));
    }
}