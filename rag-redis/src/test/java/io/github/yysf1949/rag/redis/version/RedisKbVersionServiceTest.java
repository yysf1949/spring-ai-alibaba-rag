package io.github.yysf1949.rag.redis.version;

import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
import io.github.yysf1949.rag.redis.vector.RedisIndexManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.UnifiedJedis;

import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Tests {@link RedisKbVersionService} using a Mockito {@link UnifiedJedis} stub.
 *
 * <p>jedis 5.2.0 {@code hset} has two real overloads:
 * <ul>
 *   <li>{@code hset(String, String, String)} — single field (3-arg)</li>
 *   <li>{@code hset(String, Map<String,String>)} — multi-field via Map</li>
 * </ul>
 * The production code uses both; tests follow.</p>
 */
class RedisKbVersionServiceTest {

    private UnifiedJedis jedis;
    private RedisIndexManager indexManager;
    private KbVersionService service;

    @BeforeEach
    void setUp() {
        jedis = mock(UnifiedJedis.class);
        indexManager = mock(RedisIndexManager.class);
        when(indexManager.publishPointerKey(anyString(), anyString()))
                .thenAnswer(inv -> "rag:publish:" + inv.getArgument(0) + ":" + inv.getArgument(1));
        service = new RedisKbVersionService(jedis, indexManager);
    }

    private static String metaKey(String t, String k, long v) {
        return "rag:kb-version-meta:" + t + ":" + k + ":" + v;
    }

    private static String versionsKey(String t, String k) {
        return "rag:kb-versions:" + t + ":" + k;
    }

    @Test
    void registerVersionCreatesMetaAndAddsToSet() {
        when(jedis.exists(metaKey("t1", "kb1", 1L))).thenReturn(false);

        service.registerVersion("t1", "kb1", 1L, KbVersionMeta.Status.DRAFT, "src");

        // ensureMetaExists → hset(key, Map{status, createdAt, docCount})
        verify(jedis).hset(eq(metaKey("t1", "kb1", 1L)), argThatMap(m ->
                "DRAFT".equals(m.get("status"))
                        && m.containsKey("createdAt")
                        && "0".equals(m.get("docCount"))));
        // sourceLabel → hset(key, "sourceLabel", "src") 3-arg
        verify(jedis).hset(eq(metaKey("t1", "kb1", 1L)), eq("sourceLabel"), eq("src"));
        // sadd called twice (ensureMetaExists adds once, registerVersion adds again — both safe idempotent at SET level)
        verify(jedis, org.mockito.Mockito.times(2))
                .sadd(versionsKey("t1", "kb1"), "1");
    }

    @Test
    void registerVersionNullSourceLabelSkipsSourceLabelHset() {
        when(jedis.exists(metaKey("t1", "kb1", 1L))).thenReturn(false);

        service.registerVersion("t1", "kb1", 1L, KbVersionMeta.Status.DRAFT, null);

        verify(jedis, never()).hset(eq(metaKey("t1", "kb1", 1L)),
                eq("sourceLabel"), anyString());
    }

    @Test
    void listVersionsReturnsParsedMetaNewestFirst() {
        when(jedis.smembers(versionsKey("t1", "kb1")))
                .thenReturn(Set.of("1", "3", "2"));
        when(jedis.exists(metaKey("t1", "kb1", 1L))).thenReturn(true);
        when(jedis.exists(metaKey("t1", "kb1", 2L))).thenReturn(true);
        when(jedis.exists(metaKey("t1", "kb1", 3L))).thenReturn(true);
        when(jedis.hgetAll(metaKey("t1", "kb1", 1L))).thenReturn(Map.of(
                "status", "DEPRECATED", "createdAt", "2026-01-01T00:00:00Z", "docCount", "5"));
        when(jedis.hgetAll(metaKey("t1", "kb1", 2L))).thenReturn(Map.of(
                "status", "ACTIVE", "createdAt", "2026-02-01T00:00:00Z",
                "publishedAt", "2026-02-02T00:00:00Z", "docCount", "10"));
        when(jedis.hgetAll(metaKey("t1", "kb1", 3L))).thenReturn(Map.of(
                "status", "STAGING", "createdAt", "2026-03-01T00:00:00Z", "docCount", "0"));

        List<KbVersionMeta> all = service.listVersions("t1", "kb1");
        assertEquals(3, all.size());
        assertEquals(3L, all.get(0).versionId());
        assertEquals(1L, all.get(2).versionId());
        assertEquals(KbVersionMeta.Status.ACTIVE, all.get(1).status());
        assertNotNull(all.get(1).publishedAt());
    }

    @Test
    void listVersionsEmptySetReturnsEmpty() {
        when(jedis.smembers(anyString())).thenReturn(Set.of());
        List<KbVersionMeta> all = service.listVersions("t1", "kb1");
        assertTrue(all.isEmpty());
    }

    @Test
    void getActiveVersionReadsPublishPointer() {
        when(jedis.get("rag:publish:t1:kb1")).thenReturn("42");
        assertEquals(42L, service.getActiveVersion("t1", "kb1").orElseThrow());
    }

    @Test
    void getActiveVersionEmptyReturnsOptionalEmpty() {
        when(jedis.get(anyString())).thenReturn(null);
        assertTrue(service.getActiveVersion("t1", "kb1").isEmpty());
    }

    @Test
    void getActiveVersionCorruptReturnsOptionalEmpty() {
        when(jedis.get(anyString())).thenReturn("not-a-number");
        assertTrue(service.getActiveVersion("t1", "kb1").isEmpty());
    }

    @Test
    void publishSetsActiveAndPointer() {
        when(jedis.get("rag:publish:t1:kb1")).thenReturn(null);
        when(jedis.exists(metaKey("t1", "kb1", 5L))).thenReturn(false);

        service.publish("t1", "kb1", 5L);

        // promote to ACTIVE — hset(key, Map{status=ACTIVE, publishedAt=...})
        verify(jedis).hset(eq(metaKey("t1", "kb1", 5L)), argThatMap(m ->
                "ACTIVE".equals(m.get("status")) && m.containsKey("publishedAt")));
        verify(jedis).set(eq("rag:publish:t1:kb1"), eq("5"));
    }

    @Test
    void publishDeprecatesPreviousAndPromotesNew() {
        when(jedis.get("rag:publish:t1:kb1")).thenReturn("1");
        when(jedis.exists(metaKey("t1", "kb1", 1L))).thenReturn(true);
        when(jedis.exists(metaKey("t1", "kb1", 2L))).thenReturn(false);

        service.publish("t1", "kb1", 2L);

        // v1 → DEPRECATED (setStatus uses 3-arg hset)
        verify(jedis).hset(eq(metaKey("t1", "kb1", 1L)), eq("status"), eq("DEPRECATED"));
        // v2 → ACTIVE
        verify(jedis).hset(eq(metaKey("t1", "kb1", 2L)), argThatMap(m ->
                "ACTIVE".equals(m.get("status")) && m.containsKey("publishedAt")));
        // Pointer updated
        verify(jedis).set(eq("rag:publish:t1:kb1"), eq("2"));
    }

    @Test
    void publishIdempotentWhenAlreadyActive() {
        when(jedis.get("rag:publish:t1:kb1")).thenReturn("3");
        when(jedis.exists(metaKey("t1", "kb1", 3L))).thenReturn(true);

        service.publish("t1", "kb1", 3L);

        // No hset on the active meta, no set on the pointer
        verify(jedis, never()).hset(eq(metaKey("t1", "kb1", 3L)),
                eq("status"), anyString());
        verify(jedis, never()).hset(eq(metaKey("t1", "kb1", 3L)), any(Map.class));
        verify(jedis, never()).set(anyString(), anyString());
    }

    @Test
    void rollbackRequiresExistingVersion() {
        when(jedis.exists(metaKey("t1", "kb1", 5L))).thenReturn(false);
        assertThrows(KbVersionNotFoundException.class,
                () -> service.rollback("t1", "kb1", 5L));
    }
    @Test
    void rollbackForwardsToPublish() {
        when(jedis.get("rag:publish:t1:kb1")).thenReturn("2");
        when(jedis.exists(metaKey("t1", "kb1", 1L))).thenReturn(true);
        when(jedis.exists(metaKey("t1", "kb1", 2L))).thenReturn(true);
        when(jedis.hgetAll(metaKey("t1", "kb1", 1L))).thenReturn(Map.of(
                "status", "DEPRECATED", "createdAt", "2026-01-01T00:00:00Z"));
        when(jedis.hgetAll(metaKey("t1", "kb1", 2L))).thenReturn(Map.of(
                "status", "ACTIVE", "createdAt", "2026-02-01T00:00:00Z"));

        service.rollback("t1", "kb1", 1L);

        // Should update the publish pointer to 1
        verify(jedis).set(eq("rag:publish:t1:kb1"), eq("1"));
    }

    @Test
    void resolveVersionNegativeReturnsActive() {
        when(jedis.get("rag:publish:t1:kb1")).thenReturn("7");
        assertEquals(7L, service.resolveVersion("t1", "kb1", -1L));
    }

    @Test
    void resolveVersionNegativeWithoutActiveThrows() {
        when(jedis.get(anyString())).thenReturn(null);
        assertThrows(KbVersionNotFoundException.class,
                () -> service.resolveVersion("t1", "kb1", -1L));
    }

    @Test
    void resolveVersionSpecificReturnsIfExists() {
        when(jedis.exists(metaKey("t1", "kb1", 9L))).thenReturn(true);
        when(jedis.hgetAll(metaKey("t1", "kb1", 9L))).thenReturn(Map.of(
                "status", "ACTIVE", "createdAt", "2026-01-01T00:00:00Z"));
        assertEquals(9L, service.resolveVersion("t1", "kb1", 9L));
    }

    @Test
    void resolveVersionSpecificThrowsIfMissing() {
        when(jedis.exists(metaKey("t1", "kb1", 9L))).thenReturn(false);
        assertThrows(KbVersionNotFoundException.class,
                () -> service.resolveVersion("t1", "kb1", 9L));
    }

    @Test
    void blankTenantIdRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.listVersions("", "kb1"));
        assertThrows(IllegalArgumentException.class,
                () -> service.getActiveVersion("t1", ""));
        assertThrows(IllegalArgumentException.class,
                () -> service.publish("t1", "kb1", -1L));
    }

    /** Match a Map<String,String> argument with the supplied predicate. */
    @SuppressWarnings("unchecked")
    private static Map<String, String> argThatMap(java.util.function.Predicate<Map<String, String>> p) {
        return org.mockito.ArgumentMatchers.argThat(m -> m != null && p.test((Map<String, String>) m));
    }
}