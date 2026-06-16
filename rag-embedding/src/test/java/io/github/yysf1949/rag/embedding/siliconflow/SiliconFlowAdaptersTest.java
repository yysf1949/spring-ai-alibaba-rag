package io.github.yysf1949.rag.embedding.siliconflow;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.LlmUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.core.port.RerankService;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.io.IOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for the three SiliconFlow adapters — driven against a
 * {@link MockWebServer} so the suite is hermetic (no real network, no
 * API key required). Real-key coverage lives in
 * {@code SiliconFlowIT} (gated on {@code SILICONFLOW_API_KEY}).
 */
class SiliconFlowAdaptersTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    // Short backoff so the whole retry chain fits well inside the 5s
    // per-call timeout the test configures (default backoff is 1s base).
    private static final Duration TEST_BACKOFF_MIN = Duration.ofMillis(20);
    private static final Duration TEST_BACKOFF_MAX = Duration.ofMillis(80);

    private MockWebServer server;
    private WebClient webClient;
    private SiliconFlowProperties props;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        props = makeProps(server.url("/v1/").toString());
        webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer test-key")
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @AfterEach
    void tearDown() throws IOException {
        server.shutdown();
    }

    private SiliconFlowProperties makeProps(String baseUrl) {
        SiliconFlowProperties p = new SiliconFlowProperties();
        p.setApiKey("test-key");
        p.setBaseUrl(baseUrl);
        // Force fast retries to keep tests quick.
        p.getEmbedding().setMaxRetries(2);
        p.getEmbedding().setTimeoutSeconds(5);
        p.getRerank().setMaxRetries(1);
        p.getRerank().setTimeoutSeconds(5);
        p.getLlm().setMaxRetries(1);
        p.getLlm().setTimeoutSeconds(5);
        return p;
    }

    private SiliconFlowEmbeddingGateway newGw() {
        return new SiliconFlowEmbeddingGateway(webClient, props, null,
                TEST_BACKOFF_MIN, TEST_BACKOFF_MAX);
    }

    private SiliconFlowEmbeddingGateway newGw(EmbeddingCache cache) {
        return new SiliconFlowEmbeddingGateway(webClient, props, cache,
                TEST_BACKOFF_MIN, TEST_BACKOFF_MAX);
    }

    private SiliconFlowRerankService newRr() {
        return new SiliconFlowRerankService(webClient, props,
                TEST_BACKOFF_MIN, TEST_BACKOFF_MAX);
    }

    private SiliconFlowLlmService newLl() {
        return new SiliconFlowLlmService(webClient, props,
                TEST_BACKOFF_MIN, TEST_BACKOFF_MAX);
    }

    private static String embeddingsOkJson(List<String> inputs, int dim) {
        try {
            ObjectNode root = JSON.createObjectNode();
            ArrayNode data = root.putArray("data");
            for (int i = 0; i < inputs.size(); i++) {
                ObjectNode item = data.addObject();
                item.put("index", i);
                item.put("object", "embedding");
                ArrayNode vec = item.putArray("embedding");
                // Deterministic but distinguishable entries.
                for (int d = 0; d < dim; d++) {
                    vec.add((float) Math.sin((i + 1) * (d + 1) * 0.13));
                }
            }
            root.put("model", "BAAI/bge-m3");
            root.putObject("usage").put("total_tokens", inputs.size() * 4);
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String rerankOkJson(List<Integer> indices) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("id", "rerank-test-1");
            ArrayNode results = root.putArray("results");
            double s = 1.0;
            for (int idx : indices) {
                ObjectNode r = results.addObject();
                r.put("index", idx);
                r.put("relevance_score", s);
                s -= 0.1;
            }
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String chatOkJson(String content) {
        try {
            ObjectNode root = JSON.createObjectNode();
            root.put("id", "chatcmpl-test");
            root.put("model", "Qwen/Qwen2.5-7B-Instruct");
            ArrayNode choices = root.putArray("choices");
            ObjectNode c = choices.addObject();
            c.put("index", 0);
            ObjectNode msg = c.putObject("message");
            msg.put("role", "assistant");
            msg.put("content", content);
            c.put("finish_reason", "stop");
            return JSON.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    // ─── EmbeddingGateway ───────────────────────────────────────────

    @Test
    void embedding_happyPath_callsOnce_andReturnsDim1024() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(embeddingsOkJson(List.of("hello", "world"), 1024)));

        SiliconFlowEmbeddingGateway gw = newGw();
        List<float[]> vecs = gw.embedBatch(List.of("hello", "world"));

        assertEquals(2, vecs.size());
        assertEquals(1024, vecs.get(0).length);
        assertEquals(1024, vecs.get(1).length);

        RecordedRequest req = server.takeRequest();
        assertEquals("POST", req.getMethod());
        assertEquals("/v1/embeddings", req.getPath());
        assertTrue(req.getHeader("Authorization").startsWith("Bearer "));
        // No retries on success → only 1 request enqueued.
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void embedding_cacheHit_avoidsUpstreamCall() throws Exception {
        EmbeddingCache fakeCache = new EmbeddingCache() {
            final Map<String, float[]> store = new HashMap<>();
            @Override
            public float[] get(String h) { return store.get(h); }
            @Override
            public List<float[]> getMany(List<String> hs) {
                return hs.stream().map(store::get).toList();
            }
            @Override
            public void put(String h, float[] v) { store.put(h, v); }
            @Override
            public void putMany(Map<String, float[]> e) { store.putAll(e); }
        };
        // Pre-populate cache with a 1024-dim vector.
        float[] cached = new float[1024];
        cached[0] = 0.42f;
        String hash = sha256Hex("hello");
        fakeCache.put(hash, cached);

        // Enqueue ONE response — "hello" hits cache, "world" misses → 1 upstream call.
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(embeddingsOkJson(List.of("world"), 1024)));

        SiliconFlowEmbeddingGateway gw = newGw(fakeCache);
        List<float[]> vecs = gw.embedBatch(List.of("hello", "world"));

        // 1 hit + 1 miss → 1 upstream call.
        assertEquals(2, vecs.size());
        assertEquals(0.42f, vecs.get(0)[0], 1e-6);
        assertEquals(1024, vecs.get(1).length);
        assertEquals(1, server.getRequestCount(), "expected exactly one upstream call for the miss");
    }

    @Test
    void embedding_dimMismatchInCache_refetches() throws Exception {
        EmbeddingCache fakeCache = new EmbeddingCache() {
            final Map<String, float[]> store = new HashMap<>();
            @Override
            public float[] get(String h) { return store.get(h); }
            @Override
            public List<float[]> getMany(List<String> hs) {
                return hs.stream().map(store::get).toList();
            }
            @Override
            public void put(String h, float[] v) { store.put(h, v); }
            @Override
            public void putMany(Map<String, float[]> e) { store.putAll(e); }
        };
        // Wrong-dim cached vector → gateway must refetch.
        fakeCache.put(sha256Hex("hello"), new float[768]);

        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(embeddingsOkJson(List.of("hello"), 1024)));

        SiliconFlowEmbeddingGateway gw = newGw(fakeCache);
        List<float[]> vecs = gw.embedBatch(List.of("hello"));

        assertEquals(1024, vecs.get(0).length);
        assertEquals(1, server.getRequestCount());
    }

    @Test
    void embedding_5xx_retryThenSuccess() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(502).setBody("bad gateway"));
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(embeddingsOkJson(List.of("hi"), 1024)));

        SiliconFlowEmbeddingGateway gw = newGw();
        List<float[]> vecs = gw.embedBatch(List.of("hi"));
        assertEquals(1, vecs.size());
        assertEquals(2, server.getRequestCount(), "expected one retry after 502");
    }

    @Test
    void embedding_4xx_noRetry_throws() throws Exception {
        server.enqueue(new MockResponse().setResponseCode(401).setBody("unauthorized"));
        // Should NOT be touched — 4xx is terminal.
        server.enqueue(new MockResponse().setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(embeddingsOkJson(List.of("hi"), 1024)));

        SiliconFlowEmbeddingGateway gw = newGw();
        assertThrows(EmbeddingUnavailableException.class,
                () -> gw.embedBatch(List.of("hi")));
        assertEquals(1, server.getRequestCount(), "expected NO retry on 4xx");
    }

    @Test
    void embedding_allRetriesExhausted_throws() {
        // Enqueue maxRetries+1 failures (initial + 2 retries → 3 responses).
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        }
        SiliconFlowEmbeddingGateway gw = newGw();
        assertThrows(EmbeddingUnavailableException.class,
                () -> gw.embedBatch(List.of("hi")));
        assertEquals(3, server.getRequestCount(), "expected 1 initial + 2 retries");
    }

    // ─── RerankService ──────────────────────────────────────────────

    @Test
    void rerank_happyPath_reordersByIndex() throws Exception {
        // Indices [2, 0, 1] — server says "doc at index 2 first, then 0, then 1".
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(rerankOkJson(List.of(2, 0, 1))));

        SiliconFlowRerankService rr = newRr();
        List<Chunk> pool = List.of(
                mkChunk("c0", "doc0"),
                mkChunk("c1", "doc1"),
                mkChunk("c2", "doc2"),
                mkChunk("c3", "doc3"));
        List<Chunk> out = rr.rerank("q", pool, 3);

        assertEquals(3, out.size());
        assertEquals("c2", out.get(0).chunkId());
        assertEquals("c0", out.get(1).chunkId());
        assertEquals("c1", out.get(2).chunkId());

        RecordedRequest req = server.takeRequest();
        assertEquals("/v1/rerank", req.getPath());
    }

    @Test
    void rerank_5xx_degradesToInputOrder_noThrow() throws Exception {
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(502).setBody("down"));
        }
        SiliconFlowRerankService rr = newRr();
        List<Chunk> pool = List.of(
                mkChunk("a", "alpha"),
                mkChunk("b", "beta"),
                mkChunk("c", "gamma"));
        // Contract: never throws on transient upstream error.
        List<Chunk> out = rr.rerank("q", pool, 2);
        assertEquals(2, out.size());
        assertEquals("a", out.get(0).chunkId(), "first two of input order");
        assertEquals("b", out.get(1).chunkId());
    }

    @Test
    void rerank_emptyCandidates_returnsEmpty() {
        SiliconFlowRerankService rr = newRr();
        assertTrue(rr.rerank("q", List.of(), 5).isEmpty());
    }

    // ─── LlmService ─────────────────────────────────────────────────

    @Test
    void llm_happyPath_returnsContent() throws Exception {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody(chatOkJson("the answer is 42")));

        SiliconFlowLlmService ll = newLl();
        String ans = ll.generateAnswer("tenant-A", "system+user prompt");
        assertEquals("the answer is 42", ans);
        assertEquals("Qwen/Qwen2.5-7B-Instruct", ll.modelId());

        RecordedRequest req = server.takeRequest();
        assertEquals("/v1/chat/completions", req.getPath());
    }

    @Test
    void llm_emptyResponse_throws() {
        server.enqueue(new MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("{\"choices\":[]}"));
        SiliconFlowLlmService ll = newLl();
        assertThrows(LlmUnavailableException.class,
                () -> ll.generateAnswer("tenant-A", "x"));
    }

    @Test
    void llm_5xx_exhausted_throws() {
        for (int i = 0; i < 5; i++) {
            server.enqueue(new MockResponse().setResponseCode(503).setBody("down"));
        }
        SiliconFlowLlmService ll = newLl();
        assertThrows(LlmUnavailableException.class,
                () -> ll.generateAnswer("tenant-A", "x"));
    }

    @Test
    void llm_4xx_noRetry_throws() {
        server.enqueue(new MockResponse().setResponseCode(400).setBody("bad"));
        SiliconFlowLlmService ll = newLl();
        assertThrows(LlmUnavailableException.class,
                () -> ll.generateAnswer("tenant-A", "x"));
        assertEquals(1, server.getRequestCount(), "4xx must NOT retry");
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static Chunk mkChunk(String id, String content) {
        return new Chunk(id, "tenant-A", "kb-1", "doc-1", "v1",
                "title", "section", content, java.util.Set.of("public"),
                io.github.yysf1949.rag.core.model.ChunkStatus.ACTIVE,
                java.time.Instant.now(), null, new float[1024]);
    }

    private static String sha256Hex(String s) {
        try {
            var md = java.security.MessageDigest.getInstance("SHA-256");
            byte[] h = md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(h);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}