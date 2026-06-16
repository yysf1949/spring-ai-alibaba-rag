package io.github.yysf1949.rag.eval;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.Citation;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.core.port.QAService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end eval test for the spec's §18 "退款规则问答" scenario.
 *
 * <p>Mirrors the structured fixture at
 * {@code src/test/resources/eval/refund-rule.json}. The fixture is the
 * human-readable spec artifact; this test inlines a parallel Document
 * so we don't have to ship a JSON loader for a single demo case.</p>
 *
 * <p><b>Gating</b> — three guards, all must hold:</p>
 * <ol>
 *   <li>{@code SILICONFLOW_API_KEY} — required because the staging index
 *       is created with {@code DIM=1024} (SiliconFlow BAAI/bge-m3);
 *       stub gateway only emits 16-dim vectors which RediSearch rejects.</li>
 *   <li>{@code RAG_REDIS_HOST} — points at a reachable Redis Stack (the
 *       local docker-compose {@code rag-redis-stack} instance).</li>
 *   <li>{@code REFUND_RULE_IT=1} — extra opt-in so it doesn't run on
 *       every CI invocation.</li>
 * </ol>
 *
 * <p>Run with:
 * <pre>
 *   SILICONFLOW_API_KEY=sk-... RAG_REDIS_HOST=localhost REFUND_RULE_IT=1 \
 *       mvn -pl rag-test -am test
 * </pre></p>
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
@EnabledIfEnvironmentVariable(named = "REFUND_RULE_IT", matches = "(?i)1|true")
class RefundRuleEndToEndTest {

    private static final String TENANT = "tenant-refund";
    private static final String KB_ID = "kb-refund";
    private static final long KB_VERSION = 1L;
    private static final String SOURCE_URI = "https://docs.example.com/refund-policy";
    private static final String MUST_CONTAIN = "运费退还";

    @DynamicPropertySource
    static void redisProps(DynamicPropertyRegistry r) {
        r.add("spring.rag.redis.host",
                () -> Optional.ofNullable(System.getenv("RAG_REDIS_HOST")).orElse("localhost"));
        r.add("spring.rag.redis.port",
                () -> Integer.parseInt(Optional.ofNullable(System.getenv("RAG_REDIS_PORT"))
                        .orElse("6379")));
    }

    @Autowired IngestService ingestService;
    @Autowired QAService qaService;

    @Test
    void refundRulePolicy_retrievesRelevantChunksAndAnswers() {
        // ─── step 1: ingest the refund-policy document ─────────────────
        Document doc = new Document(
                TENANT, KB_ID, KB_ID + "/doc-refund-v1", "1",
                "退款规则", SOURCE_URI,
                Set.of("ROLE_USER"),
                List.of(
                        new Document.Section("通用条款",
                                "用户在下单后 7 日内可申请无理由退款。"
                                        + MUST_CONTAIN + "规则：商品签收后 7 日内可申请运费退款。"
                                        + "运费退款金额按实际支付运费计算，特殊商品（虚拟卡、定制商品）除外。"),
                        new Document.Section("申请流程",
                                "第 1 步：登录账户进入「我的订单」。"
                                        + "第 2 步：选择目标订单点击「申请退款」。"
                                        + "第 3 步：填写退款原因并提交，客服将在 24 小时内审核。"
                                        + "审核通过后，运费退还与货款同时原路退回。"),
                        new Document.Section("特殊商品",
                                "虚拟商品、定制商品、生鲜类商品不支持 7 天无理由退款，运费亦不退还。"
                                        + "具体以商品详情页标注为准。")));

        IngestJob job = ingestService.ingestSync(doc);
        assertEquals(io.github.yysf1949.rag.core.model.IngestJobStatus.READY, job.status(),
                "ingestSync must complete to READY before publish, got: " + job);
        assertTrue(job.totalChunks() >= 1, "document must produce ≥1 chunk");
        assertEquals(0, job.failedChunks(), "no chunks should fail");

        // ─── step 2: publish (active index switch) ─────────────────────
        IngestJob published = ingestService.publish(job.jobId());
        assertEquals(io.github.yysf1949.rag.core.model.IngestJobStatus.PUBLISHED,
                published.status(), "publish must promote READY → PUBLISHED");

        // ─── step 3: ask the spec's eval question ──────────────────────
        Query q = new Query(
                TENANT, "alice", "session-refund-1",
                "用户付了运费但商品质量问题退款，运费退吗",
                Set.of("ROLE_USER"),
                5,
                new io.github.yysf1949.rag.core.model.KbVersion(TENANT, KB_ID, KB_VERSION));

        Answer answer = qaService.answer(q);

        // ─── assertion 1: reranked pool must contain the key sentence ─
        assertFalse(answer.reranked().isEmpty(),
                "after publish, retrieval must return ≥1 chunk for the refund query");
        boolean rerankedContainsPhrase = answer.reranked().stream()
                .anyMatch(c -> c.content() != null && c.content().contains(MUST_CONTAIN));
        assertTrue(rerankedContainsPhrase,
                "at least one reranked chunk must contain '" + MUST_CONTAIN
                        + "' — got reranked=" + answer.reranked().stream()
                                .map(io.github.yysf1949.rag.core.model.Chunk::content)
                                .toList());

        // ─── assertion 2: citations must include the fixture's sourceUri ─
        assertNotNull(answer.citations(), "answer.citations must not be null");
        assertFalse(answer.citations().isEmpty(),
                "answer must expose ≥1 citation once chunks are retrieved");
        boolean citationUriMatches = answer.citations().stream()
                .map(Citation::sourceUri)
                .anyMatch(SOURCE_URI::equals);
        assertTrue(citationUriMatches,
                "at least one citation.sourceUri must equal '" + SOURCE_URI
                        + "', got: " + answer.citations().stream()
                                .map(Citation::sourceUri).toList());
    }
}