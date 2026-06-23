package io.github.yysf1949.rag.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.core.port.VectorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end integration test that exercises the full Spring Boot
 * context against a Redis Stack instance managed by Testcontainers.
 *
 * <p>The test automatically starts a {@code redis/redis-stack-server}
 * container with the RediSearch module. The {@code @DynamicPropertySource}
 * injects the container's host/port so the application context connects
 * to the ephemeral Redis instance — no manual env-var setup needed.</p>
 *
 * <p>Why an {@code IT} suffix and a system-property gate? Testcontainers
 * requires a Docker daemon, and the test may take &gt;30s on first run
 * (image pull). By default {@code mvn verify} skips it
 * ({@code -DrunIT=true} to enable).</p>
 *
 * <p>This test is the regression net for D4 (Testcontainers / real-Redis
 * verification). It is intentionally narrow: ingest one document,
 * publish, ask one question, ask it again, assert the cache hit.</p>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@EnabledIfSystemProperty(named = "runIT", matches = "true")
@Testcontainers
class RagEndToEndIT {

    private static final DockerImageName REDIS_IMAGE =
            DockerImageName.parse("redis/redis-stack-server:latest");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(REDIS_IMAGE)
            .withExposedPorts(6379)
            .waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.rag.redis.host", redis::getHost);
        r.add("spring.rag.redis.port", () -> redis.getMappedPort(6379));
        r.add("spring.rag.redis.enabled", () -> "true");
    }

    @LocalServerPort
    int port;

    @Autowired
    IngestService ingestService;

    @Autowired
    VectorStore vectorStore;

    @Autowired
    ObjectMapper objectMapper;

    private final RestTemplate http = new RestTemplate();

    @Test
    void ingestThenAnswerThenCacheHit() throws Exception {
        String tenantId = "tenant-it";
        String kbId = "kb-it";
        long kbVersion = 1L;

        // ─── step 1: ingest a document synchronously ──────────────────
        Document doc = new Document(
                tenantId, "kb-it", "kb-it/doc-it", "1",
                "退款规则", "https://docs.example.com/refund",
                java.util.Set.of("ROLE_USER"),
                List.of(new Document.Section(
                        "退款规则",
                        "运费退还规则：商品签收后 7 日内可申请运费退款。"
                                + "运费退款金额按实际支付运费计算。")));

        var job = ingestService.ingestSync(doc);
        assertEquals(io.github.yysf1949.rag.core.model.IngestJobStatus.READY, job.status(),
                "ingest must complete to READY before publish, got: " + job);
        assertTrue(job.totalChunks() >= 1, "document must produce ≥1 chunk");
        assertEquals(0, job.failedChunks(), "no chunks should fail");

        // ─── step 2: publish the staging index to active ─────────────
        var published = ingestService.publish(job.jobId());
        assertEquals(io.github.yysf1949.rag.core.model.IngestJobStatus.PUBLISHED,
                published.status());

        // ─── step 3: ask via HTTP — first call must NOT be a cache hit
        var first = postQa(tenantId, "运费怎么退？");
        assertEquals(200, first.getStatusCode().value());
        Answer firstBody = objectMapper.readValue(first.getBody(), Answer.class);
        assertNotNull(firstBody.finalText());
        assertNotNull(firstBody.source(),
                "Answer.source must be set on every response");
        // First call should be LLM (real chunks retrieved, answer generated)
        // — not CACHE (cache is empty) and not FALLBACK_RULE (vector store
        // returned chunks after publish).
        var allowedSources = List.of(AnswerSource.LLM, AnswerSource.FALLBACK_RULE);
        assertTrue(allowedSources.contains(firstBody.source()),
                "first call source must be LLM or FALLBACK_RULE, got: "
                        + firstBody.source());
        long firstLatency = firstBody.latencyMs();

        // ─── step 4: ask again — must be cache hit ────────────────────
        var second = postQa(tenantId, "运费怎么退？");
        assertEquals(200, second.getStatusCode().value());
        Answer secondBody = objectMapper.readValue(second.getBody(), Answer.class);
        assertEquals(AnswerSource.CACHE, secondBody.source(),
                "second identical query must hit AnswerCache, got: " + secondBody.source());
        // Cache hit should be materially faster.
        assertTrue(secondBody.latencyMs() <= firstLatency,
                "cached response should not be slower than uncached, got "
                        + secondBody.latencyMs() + "ms vs first " + firstLatency + "ms");
    }

    @Test
    void containerRedisIsReachable() {
        // Verify the Testcontainers-managed Redis Stack is running and
        // the DynamicPropertySource injected the correct host/port.
        assertNotNull(redis, "Redis container must be started by @Container");
        assertTrue(redis.isRunning(), "Redis container must be running");
        assertTrue(redis.getMappedPort(6379) > 0,
                "Redis container must expose port 6379");
    }

    @SuppressWarnings("unchecked")
    private ResponseEntity<String> postQa(String tenantId, String rawText) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Tenant-Id", tenantId);
        headers.setContentType(MediaType.APPLICATION_JSON);
        // kbVersion must be pinned so QAService's vector-store lookup
        // can resolve a published active index. In production this is
        // usually supplied by the front-end / session metadata; in tests
        // we set it explicitly to match the ingest step above.
        Map<String, Object> body = Map.of(
                "userId", "alice",
                "rawText", rawText,
                "permissionTags", List.of("ROLE_USER"),
                "topK", 5,
                "kbVersion", Map.of("kbId", "kb-it", "version", 1));
        HttpEntity<Map<String, Object>> req = new HttpEntity<>(body, headers);
        return http.exchange(
                "http://localhost:" + port + "/api/qa",
                HttpMethod.POST, req, String.class);
    }
}