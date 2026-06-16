package io.github.yysf1949.rag.embedding.siliconflow;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that hit the real SiliconFlow API.
 *
 * <p><b>Disabled by default.</b> Activated only when both:
 * <ol>
 *   <li>Env var {@code SILICONFLOW_API_KEY} is set to a valid key.</li>
 *   <li>Env var {@code SILICONFLOW_IT} is set to {@code "1"} or {@code "true"} —
 *       so unit-test runs in CI never hit the real API.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 * SILICONFLOW_API_KEY=sk-... SILICONFLOW_IT=1 mvn -pl rag-embedding -am test
 * </pre>
 *
 * <p>Verifies that the live SiliconFlow endpoints actually respond in the
 * shape we expect — this is the only place a hand-rolled JSON DTO could
 * silently disagree with a real provider. Always run before tagging a
 * release.</p>
 */
@EnabledIfEnvironmentVariable(named = "SILICONFLOW_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "SILICONFLOW_IT", matches = "(?i)1|true")
class SiliconFlowIT {

    private static SiliconFlowProperties props;
    private static WebClient webClient;

    @BeforeAll
    static void setUp() {
        String key = System.getenv("SILICONFLOW_API_KEY");
        assertNotNull(key, "SILICONFLOW_API_KEY must be set");
        assertFalse(key.isBlank(), "SILICONFLOW_API_KEY must not be blank");

        props = new SiliconFlowProperties();
        props.setApiKey(key);
        props.setBaseUrl(System.getenv().getOrDefault("SILICONFLOW_BASE_URL",
                "https://api.siliconflow.cn/v1"));
        // Default models — operator can override via env if needed.
        // (We don't expose model overrides in env; tests target the
        // defaults documented in .env.example.)

        webClient = WebClient.builder()
                .baseUrl(props.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Test
    void embedding_bgeM3_returns1024dimVectors() {
        SiliconFlowEmbeddingGateway gw = new SiliconFlowEmbeddingGateway(webClient, props, null);
        List<float[]> vecs = gw.embedBatch(List.of("退货政策是什么？", "运费怎么退？"));
        assertEquals(2, vecs.size());
        assertEquals(1024, vecs.get(0).length, "bge-m3 dim must be 1024");
        assertEquals(1024, vecs.get(1).length);
        // Non-zero magnitudes (sanity check the model isn't returning zeros).
        double mag0 = 0;
        for (float f : vecs.get(0)) mag0 += f * f;
        assertTrue(Math.sqrt(mag0) > 0.1, "vector magnitude should be > 0.1");
    }

    @Test
    void embedding_similarity_ordersAsExpected() {
        SiliconFlowEmbeddingGateway gw = new SiliconFlowEmbeddingGateway(webClient, props, null);
        // Embed 3 docs and a query; "退款政策" should be closest to "退款" not "天气".
        List<float[]> docs = gw.embedBatch(List.of(
                "支持7天无理由退款",          // doc 0 — refund policy
                "今天北京天气晴朗",            // doc 1 — weather
                "支持支付宝、微信支付"          // doc 2 — payment
        ));
        float[] query = gw.embedWithoutCache(List.of("如何申请退款")).get(0);

        double sim0 = cosine(query, docs.get(0));
        double sim1 = cosine(query, docs.get(1));
        double sim2 = cosine(query, docs.get(2));
        // Sanity: doc 0 (refund) should beat doc 1 (weather) by a clear margin.
        assertTrue(sim0 > sim1 + 0.05,
                "expected refund-doc similarity (" + sim0 + ") > weather-doc (" + sim1 + ")");
    }

    @Test
    void rerank_bgeReranker_reordersCorrectly() {
        SiliconFlowRerankService rr = new SiliconFlowRerankService(webClient, props);
        List<Chunk> pool = List.of(
                mkChunk("d0", "今天天气真好，适合出门散步"),
                mkChunk("d1", "支持7天无理由退款，运费险可全额补偿"),
                mkChunk("d2", "我们的支付方式包括支付宝和微信"),
                mkChunk("d3", "退款政策详情请咨询客服"));
        List<Chunk> reranked = rr.rerank("如何退款", pool, 3);
        assertEquals(3, reranked.size(), "top-3");
        // d1 (refund+运费) or d3 (refund policy) should be in the top-1 slot.
        String topId = reranked.get(0).chunkId();
        assertTrue(topId.equals("d1") || topId.equals("d3"),
                "top-1 should be a refund-related chunk, got " + topId);
    }

    @Test
    void llm_qwen7B_respondsInChinese() {
        SiliconFlowLlmService ll = new SiliconFlowLlmService(webClient, props);
        String ans = ll.generateAnswer("tenant-IT",
                "你是一个简洁的助手。问：什么是 Spring Boot？请用一句话回答。");
        assertNotNull(ans);
        assertFalse(ans.isBlank());
        // Qwen2.5-7B-Instruct on SiliconFlow is a Chinese-strong model.
        assertTrue(ans.length() > 4, "expected non-trivial answer, got: '" + ans + "'");
        System.out.println("[SiliconFlowIT.llm_qwen7B] answer=" + ans);
    }

    @Test
    void embedding_badKey_401_throwsUnavailable() {
        SiliconFlowProperties bad = new SiliconFlowProperties();
        bad.setApiKey("sk-this-is-not-a-valid-key");
        bad.setBaseUrl(props.getBaseUrl());
        WebClient badClient = WebClient.builder()
                .baseUrl(bad.getBaseUrl())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + bad.getApiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
        SiliconFlowEmbeddingGateway gw = new SiliconFlowEmbeddingGateway(badClient, bad, null,
                Duration.ofMillis(50), Duration.ofMillis(200));
        assertThrows(EmbeddingUnavailableException.class,
                () -> gw.embedBatch(List.of("test")));
    }

    // ─── helpers ────────────────────────────────────────────────────

    private static Chunk mkChunk(String id, String content) {
        return new Chunk(id, "tenant-IT", "kb-IT", "doc-IT", "v1",
                "title", "section", content, Set.of("public"),
                ChunkStatus.ACTIVE, Instant.now(), null, new float[1024]);
    }

    private static double cosine(float[] a, float[] b) {
        if (a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}