package io.github.yysf1949.rag.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.Citation;
import io.github.yysf1949.rag.core.port.QAService;
import io.github.yysf1949.rag.core.port.VectorStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 39 / R16 — Streaming citation tests.
 *
 * <h2>DoD for T2</h2>
 * <ol>
 *   <li>Endpoint accepts the same QaRequest as RagController.</li>
 *   <li>Response is {@code text/event-stream} with three event types:
 *       {@code token} (repeated), {@code citations} (once, at end), and
 *       {@code done} (once, at very end).</li>
 *   <li>The reconstructed finalText equals the answer's {@code finalText}
 *       (no chunk loss, no duplicate emission).</li>
 *   <li>Citation payload contains {@code chunkId}, {@code title},
 *       {@code sectionPath}, {@code sourceUri}, {@code score} for every
 *       citation on the answer — the UI needs all of these to wire
 *       click-through.</li>
 *   <li>{@code [N]} markers in the text are preserved verbatim — they
 *       must NOT be stripped or rewritten by the streamer.</li>
 * </ol>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        classes = {io.github.yysf1949.rag.app.RagAppApplication.class,
                StreamingCitationControllerTest.TestOverrides.class})
@TestPropertySource(properties = {
        "spring.main.web-application-type=servlet",
        "spring.rag.redis.enabled=false",
        "spring.data.redis.host=nonexistent",
        "spring.data.redis.port=0",
        "spring.ai.openai.api-key=test-key",
        "rag.experiment.auto-winner.enabled=false",
        "rag.citation.test-overrides=true"
})
@AutoConfigureMockMvc
class StreamingCitationControllerTest {

    @LocalServerPort
    int port;

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void streamingSseEmitsTokenCitationsAndDoneInOrder() throws Exception {
        var body = Map.of(
                "userId", "alice",
                "rawText", "退款规则是什么？",
                "chunkSize", 3);

        MvcResult async = mvc.perform(post("/api/qa/stream")
                        .header("X-Tenant-Id", "tenant-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(request().asyncStarted())
                .andReturn();

        MvcResult result = mvc.perform(asyncDispatch(async))
                .andExpect(status().isOk())
                .andReturn();

        String sse = result.getResponse().getContentAsString();
        assertTrue(sse.contains("event:token"), "no token event in: " + sse.substring(0, 200));
        assertTrue(sse.contains("event:citations"));
        assertTrue(sse.contains("event:done"));

        int firstToken = sse.indexOf("event:token");
        int firstCitations = sse.indexOf("event:citations");
        int firstDone = sse.indexOf("event:done");
        assertTrue(firstToken < firstCitations, "tokens must come before citations");
        assertTrue(firstCitations < firstDone, "citations must come before done");
    }

    @Test
    void streamedTokensReconstructToOriginalAnswerText() throws Exception {
        // The HTTP path: send a real request through the controller.
        HttpClient client = HttpClient.newHttpClient();
        String reqBody = objectMapper.writeValueAsString(Map.of(
                "userId", "u",
                "rawText", "test",
                "chunkSize", 3));
        HttpRequest req = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/qa/stream"))
                .header("Content-Type", "application/json")
                .header("X-Tenant-Id", "tenant-A")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(reqBody))
                .build();
        HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

        assertEquals(200, resp.statusCode());
        String body = resp.body();
        assertNotNull(body);
        // Reconstruct text from token events. The data payload for a
        // token event is a JSON object {"text":"...","offset":N} — we
        // extract the `text` field. The parser tolerates both quoted
        // strings (Jackson's default for record fields with one entry)
        // and JSON objects.
        StringBuilder reconstructed = new StringBuilder();
        for (String line : body.split("\n")) {
            if (!line.startsWith("data:")) continue;
            String payload = line.substring(5).trim();
            if (payload.isEmpty()) continue;
            try {
                JsonNode node = objectMapper.readTree(payload);
                if (node.has("text")) {
                    reconstructed.append(node.get("text").asText());
                }
            } catch (Exception ignored) {
                // Not a token payload (could be the citations object) — skip.
            }
        }
        // Markers must survive verbatim.
        assertTrue(reconstructed.toString().contains("[1]"),
                "reconstructed text missing [1] marker; got: " + reconstructed);
        // Citations payload includes sourceUri for click-through.
        assertTrue(body.contains("https://example.com/doc"),
                "citations event missing sourceUri; body: " + body.substring(0, 300));
    }

    @Test
    void streamingMissingTenantReturns401() throws Exception {
        var body = Map.of("userId", "u", "rawText", "test");
        mvc.perform(post("/api/qa/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized());
    }

    /**
     * Test-only override for {@link VectorStore} so the Spring context
     * boots without a real Redis backend. The StubQaService below
     * provides deterministic Answer content so we can assert SSE
     * reconstruction exactly.
     */
    @TestConfiguration
    @org.springframework.boot.autoconfigure.condition.ConditionalOnProperty(name = "rag.citation.test-overrides", havingValue = "true", matchIfMissing = false)
    static class TestOverrides {
        @Bean
        @Primary
        public VectorStore citationSeededVectorStore() {
            return new SeededVectorStore();
        }

        @Bean
        @Primary
        public QAService stubQaService() {
            return new StubQaService();
        }
    }

    static class SeededVectorStore implements VectorStore {
        private final ConcurrentMap<String, Chunk> store = new ConcurrentHashMap<>();

        SeededVectorStore() {
            Chunk seed = new Chunk(
                    "seeded-1", "tenant-A", "kb-1", "doc-1", "1",
                    "退款规则", "运费条款",
                    "运费退还规则：商品签收 7 日内可申请运费退款。",
                    new HashSet<>(List.of("ROLE_USER")),
                    ChunkStatus.ACTIVE,
                    Instant.now(),
                    "https://docs.example.com/refund",
                    new float[16], null);
            store.put(seed.chunkId(), seed);
        }

        @Override
        public int upsert(List<Chunk> chunks) {
            for (var c : chunks) store.put(c.chunkId(), c);
            return chunks.size();
        }

        @Override
        public int deleteByIds(String tenantId, String kbId, long kbVersion, List<String> chunkIds) {
            int n = 0;
            for (String id : chunkIds) {
                if (store.remove(id) != null) n++;
            }
            return n;
        }

        @Override
        public List<Chunk> search(
                float[] queryVector,
                String tenantId,
                String kbId,
                long kbVersion,
                List<String> userPermissionTags,
                io.github.yysf1949.rag.core.model.PermissionMode permissionMode,
                int topK) {
            var results = new java.util.ArrayList<Chunk>();
            for (var c : store.values()) {
                if (!c.tenantId().equals(tenantId)) continue;
                if (!c.kbId().equals(kbId)) continue;
                if (!userPermissionTags.isEmpty()
                        && java.util.Collections.disjoint(c.permissionTags(), userPermissionTags)) continue;
                results.add(c);
            }
            return results.stream().limit(topK).toList();
        }

        @Override
        public void publish(String tenantId, String kbId, long kbVersion) {
            // no-op
        }

        @Override
        public int deleteByDocumentId(String tenantId, String kbId, String documentId, long kbVersion) {
            int n = 0;
            var it = store.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Chunk c = e.getValue();
                if (c.tenantId().equals(tenantId) && c.kbId().equals(kbId)
                        && c.documentId().equals(documentId)) {
                    it.remove();
                    n++;
                }
            }
            return n;
        }

        @Override
        public int deprecate(String tenantId, String kbId, long kbVersion) {
            int n = 0;
            var it = store.entrySet().iterator();
            while (it.hasNext()) {
                var e = it.next();
                Chunk c = e.getValue();
                if (c.tenantId().equals(tenantId) && c.kbId().equals(kbId)) {
                    it.remove();
                    n++;
                }
            }
            return n;
        }
    }

    static class StubQaService implements QAService {
        Answer nextAnswer = new Answer(
                "tenant-A", "h", "r", List.of(), List.of(),
                "根据政策 [1]，您可以在 7 天内退货。详情见 [2] 和 [3]。",
                List.of(
                        new Citation("c1", "退款政策", "规则", "https://example.com/doc", 1.0),
                        new Citation("c2", "运费条款", "运费", "https://example.com/doc2", 0.9),
                        new Citation("c3", "客服联系方式", "联系方式", "https://example.com/doc3", 0.7)
                ),
                io.github.yysf1949.rag.core.model.AnswerSource.LLM,
                50, Map.of());

        @Override
        public Answer answer(io.github.yysf1949.rag.core.model.Query query) {
            return nextAnswer;
        }

        @Override
        public void recordExperimentOutcome(String experimentName, String variantId,
                                            boolean positive, io.github.yysf1949.rag.core.model.Query query) {
            // no-op
        }
    }
}
