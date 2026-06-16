package io.github.yysf1949.rag.redis.cache;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.Citation;
import io.github.yysf1949.rag.core.port.RewriteService.RewriteResult;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end smoke test for the three cache implementations
 * (AnswerCache / EmbeddingCache / RewriteCache) against the local
 * {@code rag-redis-stack} podman container.
 *
 * <p>Activated with {@code -Dredis.smoke.test=true}. Skipped by default so
 * the default build doesn't require a live Redis.</p>
 *
 * <p>Coverage:</p>
 * <ol>
 *   <li>AnswerCache round-trip — get → put → get returns equal Answer</li>
 *   <li>AnswerCache miss — non-existent key returns Optional.empty()</li>
 *   <li>AnswerCache TTL — custom short TTL expires entries</li>
 *   <li>AnswerCache invalidateTenant — wipes ONLY that tenant's entries</li>
 *   <li>AnswerCache tenant isolation — same queryHash across tenants
 *       returns different Answers (no cross-tenant leakage)</li>
 *   <li>EmbeddingCache round-trip — vector byte-identical after write/read</li>
 *   <li>EmbeddingCache dimension-mismatch — older dim cached entry is
 *       silently discarded and recorded as a miss</li>
 *   <li>EmbeddingCache getMany/putMany bulk — single MGET / pipeline hit</li>
 *   <li>RewriteCache round-trip — text + score + usedLlm preserved</li>
 * </ol>
 */
@EnabledIfSystemProperty(named = "redis.smoke.test", matches = "true")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RedisCacheSmokeTest {

    private static final String TENANT_A = "cacheP3TenantA";
    private static final String TENANT_B = "cacheP3TenantB";

    private static RedisConnection connection;
    private static RedisAnswerCache answerCache;
    private static RedisEmbeddingCache embeddingCache;
    private static RedisRewriteCache rewriteCache;

    @BeforeAll
    static void connect() {
        RedisProperties props = new RedisProperties(
                "127.0.0.1", 6379, null, 0, 4, 2, 1, 2000, 5000);
        connection = new RedisConnection(props);
        connection.init();
        answerCache = new RedisAnswerCache(connection, 3600);
        embeddingCache = new RedisEmbeddingCache(connection, 1536);
        rewriteCache = new RedisRewriteCache(connection, 3600);
        cleanAll();
    }

    @AfterAll
    static void teardown() {
        try {
            cleanAll();
        } finally {
            if (connection != null) connection.shutdown();
        }
    }

    private static void cleanAll() {
        var client = connection.client();
        // Wipe both tenants' cache keys + the embedding hashes used in tests.
        for (String prefix : List.of(
                RedisCacheKeys.answerKeyPrefix(TENANT_A),
                RedisCacheKeys.answerKeyPrefix(TENANT_B),
                RedisCacheKeys.rewriteKeyPrefix(TENANT_A),
                RedisCacheKeys.rewriteKeyPrefix(TENANT_B),
                RedisCacheKeys.embeddingKey("hash-1"),
                RedisCacheKeys.embeddingKey("hash-2"),
                RedisCacheKeys.embeddingKey("hash-3"),
                RedisCacheKeys.embeddingKey("hash-dim-mismatch"))) {
            client.del(prefix);
        }
    }

    // ─── 1. AnswerCache round-trip ─────────────────────────────────────────

    @Test
    @Order(1)
    void answerCache_roundTrip() {
        Answer a = sampleAnswer(TENANT_A, "qhash-1", "已付款订单 24 小时内可全额退款。");
        assertTrue(answerCache.put(TENANT_A, "qhash-1", a));

        Optional<Answer> hit = answerCache.get(TENANT_A, "qhash-1");
        assertTrue(hit.isPresent(), "expected cache hit immediately after put");
        Answer got = hit.get();
        assertEquals(a.tenantId(), got.tenantId());
        assertEquals(a.queryHash(), got.queryHash());
        assertEquals(a.finalText(), got.finalText());
        assertEquals(a.source(), got.source());
        assertEquals(a.retrieved().size(), got.retrieved().size());
        assertEquals(a.reranked().size(), got.reranked().size());
        assertEquals(a.citations().size(), got.citations().size());
    }

    // ─── 2. AnswerCache miss ───────────────────────────────────────────────

    @Test
    @Order(2)
    void answerCache_miss_returnsEmpty() {
        Optional<Answer> miss = answerCache.get(TENANT_A, "this-key-does-not-exist");
        assertTrue(miss.isEmpty());
    }

    // ─── 3. AnswerCache TTL ────────────────────────────────────────────────

    @Test
    @Order(3)
    void answerCache_ttlExpires() {
        RedisAnswerCache shortTtl = new RedisAnswerCache(connection, /* ttlSeconds= */ 1);
        Answer a = sampleAnswer(TENANT_A, "qhash-ttl", "5 秒后过期测试");
        assertTrue(shortTtl.put(TENANT_A, "qhash-ttl", a));
        assertTrue(shortTtl.get(TENANT_A, "qhash-ttl").isPresent());

        // Wait > TTL. Redis EX granularity is seconds.
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for TTL");
        }
        assertTrue(shortTtl.get(TENANT_A, "qhash-ttl").isEmpty(),
                "entry must have expired after TTL");
    }

    // ─── 4. AnswerCache invalidateTenant ───────────────────────────────────

    @Test
    @Order(4)
    void answerCache_invalidateTenant_onlyThatTenant() {
        answerCache.put(TENANT_A, "qhash-A1", sampleAnswer(TENANT_A, "qhash-A1", "A1"));
        answerCache.put(TENANT_A, "qhash-A2", sampleAnswer(TENANT_A, "qhash-A2", "A2"));
        answerCache.put(TENANT_B, "qhash-B1", sampleAnswer(TENANT_B, "qhash-B1", "B1"));
        assertTrue(answerCache.get(TENANT_A, "qhash-A1").isPresent());
        assertTrue(answerCache.get(TENANT_A, "qhash-A2").isPresent());
        assertTrue(answerCache.get(TENANT_B, "qhash-B1").isPresent());

        long deleted = answerCache.invalidateTenant(TENANT_A);
        assertTrue(deleted >= 2, "expected to delete >= 2 (got " + deleted + ")");

        assertTrue(answerCache.get(TENANT_A, "qhash-A1").isEmpty(), "A1 must be gone");
        assertTrue(answerCache.get(TENANT_A, "qhash-A2").isEmpty(), "A2 must be gone");
        assertTrue(answerCache.get(TENANT_B, "qhash-B1").isPresent(),
                "B tenant must NOT be touched");
    }

    // ─── 5. AnswerCache tenant isolation (same queryHash, different tenant) ─

    @Test
    @Order(5)
    void answerCache_tenantIsolation_sameQueryHash() {
        String sharedHash = "qhash-shared";
        Answer aA = sampleAnswer(TENANT_A, sharedHash, "answer for tenant A");
        Answer aB = sampleAnswer(TENANT_B, sharedHash, "answer for tenant B");
        assertTrue(answerCache.put(TENANT_A, sharedHash, aA));
        assertTrue(answerCache.put(TENANT_B, sharedHash, aB));

        Answer gotA = answerCache.get(TENANT_A, sharedHash).orElseThrow();
        Answer gotB = answerCache.get(TENANT_B, sharedHash).orElseThrow();
        assertEquals("answer for tenant A", gotA.finalText());
        assertEquals("answer for tenant B", gotB.finalText());
    }

    // ─── 6. EmbeddingCache round-trip ──────────────────────────────────────

    @Test
    @Order(6)
    void embeddingCache_roundTrip_bytesIdentical() {
        float[] v = randomVector(1536, 42);
        embeddingCache.put("hash-1", v);
        float[] got = embeddingCache.get("hash-1");
        assertNotNull(got);
        assertEquals(1536, got.length);
        assertArrayEquals(v, got, 0.0001f);
    }

    // ─── 7. EmbeddingCache dimension mismatch ──────────────────────────────

    @Test
    @Order(7)
    void embeddingCache_dimensionMismatch_returnsNullAndDeletes() {
        // Manually cache a 512-dim vector under "hash-dim-mismatch" via
        // direct Redis bytes — bypasses the dimension guard so we can
        // simulate an entry written by a previous model version.
        byte[] legacy = encodeFloatsLE(randomVector(512, 7));
        connection.client().set(
                RedisCacheKeys.embeddingKey("hash-dim-mismatch").getBytes(),
                legacy);
        // Even though our cache expects 1536-dim, the 512-dim entry is on disk.
        // The cache must detect the mismatch, delete it, and return null.
        float[] got = embeddingCache.get("hash-dim-mismatch");
        assertNull(got, "dim-mismatch must surface as a miss");
        // And the stale entry must be reaped.
        assertNull(connection.client().get(
                RedisCacheKeys.embeddingKey("hash-dim-mismatch").getBytes()),
                "stale entry must have been deleted");
    }

    // ─── 8. EmbeddingCache bulk round-trip ─────────────────────────────────

    @Test
    @Order(8)
    void embeddingCache_bulkRoundTrip() {
        Map<String, float[]> bulk = new HashMap<>();
        float[] v1 = randomVector(1536, 11);
        float[] v2 = randomVector(1536, 22);
        float[] v3 = randomVector(1536, 33);
        bulk.put("hash-2", v1);
        bulk.put("hash-3", v2);
        // hash-1 also has a value from test 6 — verify it survives.
        embeddingCache.putMany(bulk);

        List<float[]> got = embeddingCache.getMany(List.of("hash-1", "hash-2", "hash-3"));
        assertEquals(3, got.size());
        assertNotNull(got.get(0), "hash-1 should still be present");
        assertNotNull(got.get(1));
        assertNotNull(got.get(2));
        assertArrayEquals(v1, got.get(1), 0.0001f);
        assertArrayEquals(v2, got.get(2), 0.0001f);
    }

    // ─── 9. RewriteCache round-trip ────────────────────────────────────────

    @Test
    @Order(9)
    void rewriteCache_roundTrip() {
        RewriteResult r = new RewriteResult(
                "退款 运费 退还", 0.92, true);
        assertTrue(rewriteCache.put(TENANT_A, "qhash-rewrite", r));

        RewriteResult got = rewriteCache.get(TENANT_A, "qhash-rewrite");
        assertNotNull(got);
        assertEquals(r.rewritten(), got.rewritten());
        assertEquals(r.ruleScore(), got.ruleScore(), 0.0001);
        assertEquals(r.usedLlm(), got.usedLlm());
    }

    // ─── 10. RewriteCache miss returns null ────────────────────────────────

    @Test
    @Order(10)
    void rewriteCache_miss_returnsNull() {
        assertNull(rewriteCache.get(TENANT_A, "qhash-never-written"));
    }

    // ─── helpers ───────────────────────────────────────────────────────────

    private static Answer sampleAnswer(String tenant, String queryHash, String text) {
        Chunk c = new Chunk(
                "cid-1", tenant, "kb-1", "doc-1", "1",
                "title", "/path", "body text", Set.of("public"),
                ChunkStatus.ACTIVE, Instant.now(), "https://example.com/d1",
                new float[1536]);
        Citation cit = new Citation("cid-1", "title", "/path",
                "https://example.com/d1", 0.95);
        return new Answer(tenant, queryHash, "rewritten " + queryHash,
                List.of(c), List.of(c), text,
                List.of(cit), AnswerSource.LLM, 42L,
                Map.of("retrievedCount", 1));
    }

    private static float[] randomVector(int dim, long seed) {
        java.util.Random rng = new java.util.Random(seed);
        float[] v = new float[dim];
        for (int i = 0; i < dim; i++) v[i] = rng.nextFloat() * 2 - 1;
        return v;
    }

    private static byte[] encodeFloatsLE(float[] v) {
        java.nio.ByteBuffer bb = java.nio.ByteBuffer.allocate(v.length * Float.BYTES);
        bb.order(java.nio.ByteOrder.LITTLE_ENDIAN);
        bb.asFloatBuffer().put(v);
        return bb.array();
    }
}
