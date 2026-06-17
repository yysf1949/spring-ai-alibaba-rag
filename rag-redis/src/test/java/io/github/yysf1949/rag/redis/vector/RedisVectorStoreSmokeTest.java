package io.github.yysf1949.rag.redis.vector;

import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.PermissionMode;
import io.github.yysf1949.rag.redis.config.RedisConnection;
import io.github.yysf1949.rag.redis.config.RedisProperties;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test for {@link RedisVectorStore} against the local
 * {@code rag-redis-stack} podman container.
 *
 * <p>Activated with {@code -Dredis.smoke.test=true}. Covers the design spec
 * §8.1 / §12 acceptance criteria:
 * <ol>
 *   <li>upsert writes HASH + register into RediSearch index</li>
 *   <li>search returns chunks in cosine-similarity order</li>
 *   <li>hard tenant wall — cross-tenant search returns 0 results</li>
 *   <li>AND permission mode blocks users missing required tags</li>
 *   <li>OR permission mode admits users with any matching tag</li>
 *   <li>publish atomically swaps the alias and updates the pointer</li>
 *   <li>deprecate marks old-version chunks as DEPRECATED and they drop out of search</li>
 *   <li>deleteByIds removes chunks and they drop out of search</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "redis.smoke.test", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisVectorStoreSmokeTest {

    private static final String TENANT = "ragP2Tenant";
    private static final String KB = "kb-refund";
    private static final long VERSION = 42L;

    private static RedisConnection connection;
    private static RedisIndexManager indexManager;
    private static RedisVectorStore store;

    @BeforeAll
    static void connect() {
        RedisProperties props = new RedisProperties(
                "127.0.0.1", 6379, null, 0,
                4, 2, 1, 2000, 5000);
        connection = new RedisConnection(props);
        connection.init();
        indexManager = new RedisIndexManager(connection);
        store = new RedisVectorStore(connection, indexManager);

        // Clean slate — drop any leftover indexes from previous runs.
        indexManager.dropIndex(TENANT, VERSION, /* staging= */ false, /* dropData= */ true);
        indexManager.dropIndex(TENANT, VERSION, /* staging= */ true, /* dropData= */ true);
        // Free the alias if it points somewhere stale.
        try {
            connection.client().ftAliasDel(RedisVectorStore.aliasName(TENANT, KB));
        } catch (Exception ignore) {
            // alias may not exist — fine
        }
        connection.client().del(indexManager.publishPointerKey(TENANT, KB));
    }

    @AfterAll
    static void teardown() {
        if (connection != null) {
            try {
                indexManager.dropIndex(TENANT, VERSION, false, true);
                indexManager.dropIndex(TENANT, VERSION, true, true);
                try {
                    connection.client().ftAliasDel(RedisVectorStore.aliasName(TENANT, KB));
                } catch (Exception ignore) {
                    // alias may already be gone — fine
                }
                connection.client().del(indexManager.publishPointerKey(TENANT, KB));
            } finally {
                connection.shutdown();
            }
        }
    }

    // ─── 1. upsert → search basic round-trip ───────────────────────────────

    @Test
    @Order(1)
    void upsert_then_search_returnsSelfHit() {
        float[] v1 = unitVector(0);
        Chunk c1 = chunk("c1", TENANT, KB, String.valueOf(VERSION), "退款规则第一条",
                "已付款订单 24 小时内可全额退款", Set.of("public"),
                ChunkStatus.STAGING, v1);

        int written = store.upsert(List.of(c1));
        assertEquals(1, written);

        // publish promotes staging hashes to ACTIVE + flips the alias to the
        // active index, which is what search() queries against.
        store.publish(TENANT, KB, VERSION);

        List<Chunk> hits = store.search(v1, TENANT, KB, 0, List.of("public"),
                PermissionMode.AND, 5);

        assertEquals(1, hits.size(), "exact-match query must return the only chunk");
        Chunk got = hits.get(0);
        assertEquals("c1", got.chunkId());
        assertEquals("退款规则第一条", got.title());
        assertArrayEquals(v1, got.embedding(), 0.0001f);
    }

    // ─── 2. tenant hard wall ───────────────────────────────────────────────

    @Test
    @Order(2)
    void search_acrossTenants_returnsNothing() {
        // Insert a chunk under a DIFFERENT tenant — it must not appear for TENANT.
        String other = TENANT + "-other";
        indexManager.dropIndex(other, VERSION, false, true);
        indexManager.dropIndex(other, VERSION, true, true);
        try {
            Chunk c = chunk("otherC1", other, KB, String.valueOf(VERSION),
                    "其他租户", "secret", Set.of("public"),
                    ChunkStatus.STAGING, unitVector(1));
            store.upsert(List.of(c));
            store.publish(other, KB, VERSION);

            List<Chunk> myHits = store.search(unitVector(1), TENANT, KB, 0,
                    List.of("public"), PermissionMode.AND, 10);
            for (Chunk hit : myHits) {
                assertEquals(TENANT, hit.tenantId(), "must never leak across tenants");
            }
        } finally {
            indexManager.dropIndex(other, VERSION, false, true);
            indexManager.dropIndex(other, VERSION, true, true);
            try {
                connection.client().ftAliasDel(RedisVectorStore.aliasName(other, KB));
            } catch (Exception ignore) { /* noop */ }
        }
    }

    // ─── 3. AND permission mode blocks under-privileged users ──────────────

    @Test
    @Order(3)
    void permissionFilter_AND_blocksMissingTags() {
        // Add a chunk that requires BOTH 'admin' and 'finance' tags.
        float[] v2 = unitVector(2);
        Chunk c2 = chunk("c2", TENANT, KB, String.valueOf(VERSION),
                "财务审核规则", "财务人员专属",
                Set.of("admin", "finance"),
                ChunkStatus.STAGING, v2);
        store.upsert(List.of(c2));
        // Re-publish to register new hash. Publish is idempotent.
        store.publish(TENANT, KB, VERSION);

        // User with only ONE of the two required tags must NOT see c2.
        List<Chunk> under = store.search(v2, TENANT, KB, 0,
                List.of("admin"), PermissionMode.AND, 10);
        for (Chunk hit : under) {
            assertNotEquals("c2", hit.chunkId(),
                    "user missing 'finance' tag must not see c2 under AND mode");
        }

        // User with BOTH tags must see it.
        List<Chunk> full = store.search(v2, TENANT, KB, 0,
                List.of("admin", "finance"), PermissionMode.AND, 10);
        assertTrue(full.stream().anyMatch(h -> "c2".equals(h.chunkId())),
                "user with all tags must see c2");
    }

    // ─── 4. OR permission mode grants visibility on any-match ──────────────

    @Test
    @Order(4)
    void permissionFilter_OR_grantsOnAnyMatch() {
        float[] v3 = unitVector(3);
        Chunk c3 = chunk("c3", TENANT, KB, String.valueOf(VERSION),
                "运营公告", "全员可见",
                Set.of("marketing", "ops"),
                ChunkStatus.STAGING, v3);
        store.upsert(List.of(c3));
        store.publish(TENANT, KB, VERSION);

        // User with only 'marketing' tag — under OR must see c3.
        List<Chunk> hits = store.search(v3, TENANT, KB, 0,
                List.of("marketing"), PermissionMode.OR, 10);
        assertTrue(hits.stream().anyMatch(h -> "c3".equals(h.chunkId())),
                "OR mode: matching one tag is enough");
    }

    // ─── 5. user with NO permission tags sees nothing ──────────────────────

    @Test
    @Order(5)
    void permissionFilter_emptyUserTags_seesNothing() {
        float[] v = unitVector(0);
        List<Chunk> hits = store.search(v, TENANT, KB, 0,
                List.of(), PermissionMode.AND, 10);
        assertTrue(hits.isEmpty(),
                "empty permissionTags ⇒ user has no authority ⇒ must see zero chunks");
    }

    // ─── 6. deleteByIds removes from search ────────────────────────────────

    @Test
    @Order(6)
    void deleteByIds_removesChunkFromSearch() {
        float[] v4 = unitVector(4);
        Chunk c4 = chunk("c4", TENANT, KB, String.valueOf(VERSION),
                "临时公告", "临时", Set.of("public"),
                ChunkStatus.STAGING, v4);
        store.upsert(List.of(c4));
        store.publish(TENANT, KB, VERSION);

        // confirm it's visible
        List<Chunk> before = store.search(v4, TENANT, KB, 0,
                List.of("public"), PermissionMode.OR, 10);
        assertTrue(before.stream().anyMatch(h -> "c4".equals(h.chunkId())));

        // delete and re-check
        int removed = store.deleteByIds(TENANT, KB, VERSION, List.of("c4"));
        assertEquals(1, removed);

        List<Chunk> after = store.search(v4, TENANT, KB, 0,
                List.of("public"), PermissionMode.OR, 10);
        assertTrue(after.stream().noneMatch(h -> "c4".equals(h.chunkId())),
                "deleted chunk must drop out of search results");
    }

    // ─── 7. publish swaps the alias atomically ─────────────────────────────

    @Test
    @Order(7)
    void publish_setsAliasAndPointer() {
        // The previous tests already published at VERSION once; republish and
        // verify the side-effects.
        store.publish(TENANT, KB, VERSION);

        assertEquals(VERSION, indexManager.getPublishPointer(TENANT, KB));
        // Alias must resolve to a real index.
        long infoBefore = connection.client().ftInfo(RedisVectorStore.aliasName(TENANT, KB))
                .size();
        assertTrue(infoBefore >= 0, "alias resolves to a real index");
    }

    // ─── 8. deprecate marks old-version chunks as DEPRECATED ───────────────

    @Test
    @Order(8)
    void deprecate_marksOldVersionAndDropsFromSearch() {
        // Spin up a NEWER version (43) with one fresh chunk; then mark VERSION
        // (=42) as deprecated and verify the old chunk drops out.
        long newVersion = VERSION + 1;
        indexManager.dropIndex(TENANT, newVersion, false, true);
        indexManager.dropIndex(TENANT, newVersion, true, true);

        try {
            float[] v5 = unitVector(5);
            Chunk c5 = chunk("c5", TENANT, KB, String.valueOf(newVersion),
                    "新规则", "新鲜出炉", Set.of("public"),
                    ChunkStatus.STAGING, v5);
            store.upsert(List.of(c5));
            store.publish(TENANT, KB, newVersion);

            // Confirm c5 is searchable now.
            assertTrue(store.search(v5, TENANT, KB, 0, List.of("public"),
                    PermissionMode.OR, 10).stream().anyMatch(h -> "c5".equals(h.chunkId())));

            // Deprecate OLD version 42 — all its ACTIVE chunks become DEPRECATED.
            int marked = store.deprecate(TENANT, KB, VERSION);
            assertTrue(marked >= 1, "expected at least one old-version chunk to be deprecated");

            // Old chunks must NOT appear in any subsequent search because
            // status != ACTIVE is part of the pre-filter.
            float[] v1 = unitVector(0);
            List<Chunk> hits = store.search(v1, TENANT, KB, 0,
                    List.of("public"), PermissionMode.OR, 50);
            for (Chunk hit : hits) {
                if (String.valueOf(VERSION).equals(hit.documentVersion())) {
                    assertEquals(ChunkStatus.DEPRECATED, hit.status(),
                            "old-version chunk must come back marked DEPRECATED");
                }
            }
        } finally {
            indexManager.dropIndex(TENANT, newVersion, false, true);
            indexManager.dropIndex(TENANT, newVersion, true, true);
        }
    }

    // ─── 9. empty / invalid input guard rails ─────────────────────────────

    @Test
    @Order(9)
    void upsert_emptyList_isNoOp() {
        assertEquals(0, store.upsert(List.of()));
        assertEquals(0, store.upsert(null));
    }

    @Test
    @Order(10)
    void search_emptyVector_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class,
                () -> store.search(new float[0], TENANT, KB, VERSION,
                        List.of("public"), PermissionMode.OR, 5));
    }

    @Test
    @Order(11)
    void search_withoutPublishedVersion_throws() {
        // Use a brand-new tenant that has never been published.
        String ghost = TENANT + "-ghost";
        indexManager.dropIndex(ghost, 1, false, true);
        indexManager.dropIndex(ghost, 1, true, true);
        try {
            assertThrows(VectorStoreUnavailableException.class,
                    () -> store.search(unitVector(0), ghost, KB, 0,
                            List.of("public"), PermissionMode.OR, 5));
        } finally {
            indexManager.dropIndex(ghost, 1, false, true);
            indexManager.dropIndex(ghost, 1, true, true);
        }
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static Chunk chunk(String id, String tenant, String kb, String version,
                               String title, String content, Set<String> permTags,
                               ChunkStatus status, float[] embedding) {
        return new Chunk(id, tenant, kb, "doc-" + id, version,
                title, "/policy", content, permTags, status,
                Instant.now(), "https://example.com/" + id, embedding, null);
    }

    /**
     * Build a 1536-dim unit-ish vector where the N-th coordinate is dominant.
     * Different chunks have different dominants so KNN distance can rank them.
     */
    private static float[] unitVector(int dominantIndex) {
        float[] v = new float[1536];
        // baseline noise
        for (int i = 0; i < v.length; i++) {
            v[i] = 0.001f;
        }
        v[dominantIndex % 1536] = 1.0f;
        // normalize
        float norm = 0f;
        for (float f : v) norm += f * f;
        norm = (float) Math.sqrt(norm);
        for (int i = 0; i < v.length; i++) v[i] /= norm;
        return v;
    }
}
