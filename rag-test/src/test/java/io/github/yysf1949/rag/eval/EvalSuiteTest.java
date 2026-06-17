package io.github.yysf1949.rag.eval;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import io.github.yysf1949.rag.core.model.*;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.core.port.QAService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-end eval suite for the RAG system.
 *
 * <p>Loads all JSON fixtures from {@code src/test/resources/eval/*.json},
 * ingests each document, publishes, runs QA, evaluates metrics, writes
 * a report, and asserts a pass rate ≥50%.</p>
 *
 * <p><b>Gating</b> — three guards, all must hold:</p>
 * <ol>
 *   <li>{@code SILICONFLOW_API_KEY} — required because the staging index
 *       is created with {@code DIM=1024} (SiliconFlow BAAI/bge-m3);
 *       stub gateway only emits 16-dim vectors which RediSearch rejects.</li>
 *   <li>{@code RAG_REDIS_HOST} — points at a reachable Redis Stack.</li>
 *   <li>{@code EVAL_SUITE} — opt-in so it doesn't run on every CI invocation.</li>
 * </ol>
 *
 * <p>Run with:</p>
 * <pre>
 *   SILICONFLOW_API_KEY=sk-... RAG_REDIS_HOST=localhost EVAL_SUITE=1 \
 *       mvn -pl rag-test -am test -Dtest=EvalSuiteTest
 * </pre>
 */
@SpringBootTest(
        classes = io.github.yysf1949.rag.app.RagAppApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.rag.redis.enabled=true",
                "rag.siliconflow.enabled=true"
        })
@EnabledIfEnvironmentVariable(named = "SILICONFLOW_API_KEY", matches = ".+")
@EnabledIfEnvironmentVariable(named = "RAG_REDIS_HOST", matches = ".+")
@EnabledIfEnvironmentVariable(named = "EVAL_SUITE", matches = "(?i)1|true")
class EvalSuiteTest {

    private static final Logger log = LoggerFactory.getLogger(EvalSuiteTest.class);

    @Autowired IngestService ingestService;
    @Autowired QAService qaService;

    private final ObjectMapper mapper = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private final EvaluationService evaluator = new EvaluationService();
    private final List<EvalResult> results = new ArrayList<>();

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.rag.redis.host",
                () -> System.getenv().getOrDefault("RAG_REDIS_HOST", "localhost"));
        r.add("spring.rag.redis.port",
                () -> Integer.parseInt(System.getenv().getOrDefault("RAG_REDIS_PORT", "6379")));
    }

    @Test
    void evalSuite() throws IOException {
        File evalDir = new File("src/test/resources/eval");
        File[] fixtures = evalDir.listFiles((dir, name) -> name.endsWith(".json"));
        assertNotNull(fixtures, "eval fixtures directory must exist");
        assertTrue(fixtures.length >= 2, "at least 2 fixtures required");

        for (File fixtureFile : fixtures) {
            if ("refund-rule.json".equals(fixtureFile.getName())) {
                continue; // already has dedicated test
            }
            EvalFixture fixture = mapper.readValue(fixtureFile, EvalFixture.class);
            String name = fixtureFile.getName().replace(".json", "");
            try {
                evalOne(fixture, name);
            } catch (Exception e) {
                log.error("Eval fixture '{}' failed: {}", name, e.getMessage(), e);
                results.add(new EvalResult(name, 0.0, 0.0, 0.0, false));
            }
        }

        // Write report
        String outputDir = System.getProperty("eval.output.dir",
                System.getenv().getOrDefault("EVAL_OUTPUT_DIR", "docs/eval"));
        new File(outputDir).mkdirs();
        mapper.writeValue(new File(outputDir, "eval-report.json"), results);
        log.info("Eval report written to {}/eval-report.json", outputDir);

        // Assert: at least 50% pass rate
        long passed = results.stream().filter(EvalResult::pass).count();
        double passRate = results.isEmpty() ? 0 : (double) passed / results.size();
        log.info("Eval suite: {}/{} passed ({}%)", passed, results.size(),
                String.format("%.0f", passRate * 100));
        assertTrue(passRate >= 0.5,
                "Eval pass rate must be >= 50%, got " + String.format("%.1f", passRate * 100) + "%");
    }

    private void evalOne(EvalFixture fixture, String name) {
        // Build Document from fixture
        String tenantId = fixture.document().tenantId();
        String kbId = fixture.document().kbId();
        String docId = fixture.document().documentId();

        List<Document.Section> sections = fixture.document().sections().stream()
                .map(s -> new Document.Section(s.heading(), s.body()))
                .toList();

        Document doc = new Document(
                tenantId, kbId, docId, String.valueOf(fixture.version()),
                fixture.document().title(), fixture.sourceUri(),
                Set.copyOf(fixture.permissionTags()),
                sections);

        // Ingest
        IngestJob job = ingestService.ingestSync(doc);
        assertEquals(IngestJobStatus.READY, job.status(),
                "ingestSync must complete to READY for fixture: " + name);
        assertTrue(job.totalChunks() >= 1, "document must produce >=1 chunk for: " + name);

        // Publish
        IngestJob published = ingestService.publish(job.jobId());
        assertEquals(IngestJobStatus.PUBLISHED, published.status(),
                "publish must promote READY -> PUBLISHED for: " + name);

        // Query
        Query q = new Query(
                tenantId,
                fixture.query().userId(),
                "session-" + name,
                fixture.query().rawText(),
                Set.copyOf(fixture.query().permissionTags()),
                fixture.query().topK(),
                new KbVersion(tenantId, kbId, fixture.version()));

        Answer answer = qaService.answer(q);

        // Evaluate with expected assertions
        EvalResult result = evaluator.evaluate(
                answer,
                fixture.expected().expectedChunkIds() == null ? List.of() : fixture.expected().expectedChunkIds(),
                List.of(fixture.expected().mustContainSourceUri()),
                List.of(fixture.expected().mustContainSubstring()),
                name);
        results.add(result);
    }
}