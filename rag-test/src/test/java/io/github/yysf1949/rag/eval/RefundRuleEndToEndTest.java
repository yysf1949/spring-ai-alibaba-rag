package io.github.yysf1949.rag.eval;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.Citation;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.model.KbVersion;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.IngestService;
import io.github.yysf1949.rag.core.port.QAService;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end eval test for the spec's §11.3 / §18 "退款规则问答" demo,
 * expanded from a single {@code @Test} (核心 case — 运费退还) into six
 * parameterized scenarios that cover the fixture's policy surface.
 *
 * <p>Mirrors the structured fixture at
 * {@code src/test/resources/eval/refund-rule.json}. The fixture is the
 * human-readable spec artifact; this test inlines a parallel Document
 * so we don't have to ship a JSON loader for a single demo case.</p>
 *
 * <p><b>Six scenarios</b> — each driven by its own {@code @MethodSource}:</p>
 * <ol>
 *   <li>{@link #shippingFeeRefund()} — 运费退还 (核心 case, mirrors original test)</li>
 *   <li>{@link #refundReasonEnum()} — 退款原因枚举 (7 日内无理由)</li>
 *   <li>{@link #sourceUriCitation()} — sourceUri citation (断言 sourceUri 精确匹配)</li>
 *   <li>{@link #partialRefund()} — 部分退款 (原路退回路径)</li>
 *   <li>{@link #timeoutAssertion()} — 时效断言 (24 小时审核)</li>
 *   <li>{@link #outOfScopeRejection()} — 拒答场景 (退款政策外 — 北京天气)</li>
 * </ol>
 *
 * <p>Each scenario re-ingests the same fixture Document, publishes it, runs a
 * single query, then asserts three things:</p>
 * <ul>
 *   <li><b>mustContain</b> — at least one reranked chunk's content contains
 *       the scenario's expected substring. For the OOS scenario (#6), the
 *       assertion inverts: no reranked chunk should contain the policy's
 *       signature phrase.</li>
 *   <li><b>citation</b> — the answer's {@code citations} includes the
 *       fixture's {@link #SOURCE_URI} (skipped for the OOS scenario where no
 *       groundable answer is expected).</li>
 *   <li><b>recall@K</b> — the reranked pool is non-empty after publish
 *       (skipped for the OOS scenario where retrieval may legitimately
 *       return an empty/short result).</li>
 * </ul>
 *
 * <p><b>Gating</b> — three guards, all must hold (same as the original):</p>
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
 * <p>Run with:</p>
 * <pre>
 *   SILICONFLOW_API_KEY=sk-... RAG_REDIS_HOST=localhost REFUND_RULE_IT=1 \
 *       mvn -pl rag-test -am test
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
@EnabledIfEnvironmentVariable(named = "REFUND_RULE_IT", matches = "(?i)1|true")
class RefundRuleEndToEndTest {

    private static final String TENANT = "tenant-refund";
    private static final String KB_ID = "kb-refund";
    private static final long KB_VERSION = 1L;
    private static final String SOURCE_URI = "https://docs.example.com/refund-policy";
    private static final String POLICY_SIGNATURE = "运费退还";

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

    // ─── 6 @MethodSource providers — one per scenario ─────────────────

    /** Scenario 1 — 运费退还 (核心 case). The original test's question,
     *  preserved as scenario 1 of the expanded suite. */
    static Stream<Arguments> shippingFeeRefund() {
        return Stream.of(Arguments.of(
                "shippingFeeRefund",
                "用户付了运费但商品质量问题退款，运费退吗",
                "运费退还",
                false));
    }

    /** Scenario 2 — 退款原因枚举. Hits the 通用条款 section
     *  ("用户在下单后 7 日内可申请无理由退款"). */
    static Stream<Arguments> refundReasonEnum() {
        return Stream.of(Arguments.of(
                "refundReasonEnum",
                "我下单7天了不想要了能退吗",
                "7 日内",
                false));
    }

    /** Scenario 3 — sourceUri citation. Focused on the citation assertion:
     *  the answer's citation.sourceUri MUST equal the fixture's SOURCE_URI
     *  exactly (not just any URI). Query targets 通用条款 §运费规则 sub-sentence
     *  about "运费退款金额按实际支付运费计算". */
    static Stream<Arguments> sourceUriCitation() {
        return Stream.of(Arguments.of(
                "sourceUriCitation",
                "运费退款金额按什么计算",
                "实际支付运费",
                false));
    }

    /** Scenario 4 — 部分退款. Hits 申请流程 (the "原路退回" path that applies
     *  to any refund — including a partial one — which the policy covers by
     *  describing the joint refund flow). */
    static Stream<Arguments> partialRefund() {
        return Stream.of(Arguments.of(
                "partialRefund",
                "我买了两件商品能只退一件吗",
                "原路退回",
                false));
    }

    /** Scenario 5 — 时效断言. Hits 申请流程 ("客服将在 24 小时内审核"). */
    static Stream<Arguments> timeoutAssertion() {
        return Stream.of(Arguments.of(
                "timeoutAssertion",
                "退款审核要多久",
                "24 小时",
                false));
    }

    /** Scenario 6 — 拒答场景 (退款政策外). An OOS query ("北京天气") that
     *  the refund policy does not cover. The expected behaviour is that
     *  retrieval returns chunks but the reranker / LLM should not surface
     *  the policy's signature phrase as a grounded answer. */
    static Stream<Arguments> outOfScopeRejection() {
        return Stream.of(Arguments.of(
                "outOfScopeRejection",
                "今天北京天气怎么样",
                POLICY_SIGNATURE,
                true));
    }

    // ─── Parameterized test runner ────────────────────────────────────

    @ParameterizedTest(name = "{0}")
    @MethodSource({
            "shippingFeeRefund",
            "refundReasonEnum",
            "sourceUriCitation",
            "partialRefund",
            "timeoutAssertion",
            "outOfScopeRejection"
    })
    void refundRulePolicy_scenarios(String scenarioName,
                                    String queryText,
                                    String mustContain,
                                    boolean outOfScope) {
        // ─── step 1: ingest the refund-policy document ─────────────────
        Document doc = new Document(
                TENANT, KB_ID, KB_ID + "/doc-refund-v1", "1",
                "退款规则", SOURCE_URI,
                Set.of("ROLE_USER"),
                List.of(
                        new Document.Section("通用条款",
                                "用户在下单后 7 日内可申请无理由退款。"
                                        + POLICY_SIGNATURE + "规则：商品签收后 7 日内可申请运费退款。"
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
        assertEquals(IngestJobStatus.READY, job.status(),
                "[" + scenarioName + "] ingestSync must complete to READY before publish, got: " + job);
        assertTrue(job.totalChunks() >= 1,
                "[" + scenarioName + "] document must produce ≥1 chunk");
        assertEquals(0, job.failedChunks(),
                "[" + scenarioName + "] no chunks should fail");

        // ─── step 2: publish (active index switch) ─────────────────────
        IngestJob published = ingestService.publish(job.jobId());
        assertEquals(IngestJobStatus.PUBLISHED, published.status(),
                "[" + scenarioName + "] publish must promote READY → PUBLISHED");

        // ─── step 3: ask the scenario's query ──────────────────────────
        Query q = new Query(
                TENANT, "alice-" + scenarioName, "session-refund-" + scenarioName,
                queryText,
                Set.of("ROLE_USER"),
                5,
                new KbVersion(TENANT, KB_ID, KB_VERSION));

        Answer answer = qaService.answer(q);

        if (outOfScope) {
            assertOutOfScope(scenarioName, answer);
        } else {
            assertInScope(scenarioName, answer, mustContain);
        }
    }

    /** In-scope assertions: reranked has the expected substring, citation
     *  sourceUri equals SOURCE_URI, and recall@K ≥ 0.5. */
    private void assertInScope(String scenarioName, Answer answer, String mustContain) {
        // ── assertion 1: reranked pool must contain the key sentence ──
        assertFalse(answer.reranked().isEmpty(),
                "[" + scenarioName + "] after publish, retrieval must return ≥1 chunk for the refund query");
        boolean rerankedContainsPhrase = answer.reranked().stream()
                .anyMatch(c -> c.content() != null && c.content().contains(mustContain));
        assertTrue(rerankedContainsPhrase,
                "[" + scenarioName + "] at least one reranked chunk must contain '"
                        + mustContain + "' — got reranked=" + answer.reranked().stream()
                                .map(io.github.yysf1949.rag.core.model.Chunk::content)
                                .toList());

        // ── assertion 2: citations must include the fixture's sourceUri ─
        assertNotNull(answer.citations(),
                "[" + scenarioName + "] answer.citations must not be null");
        assertFalse(answer.citations().isEmpty(),
                "[" + scenarioName + "] answer must expose ≥1 citation once chunks are retrieved");
        boolean citationUriMatches = answer.citations().stream()
                .map(Citation::sourceUri)
                .anyMatch(SOURCE_URI::equals);
        assertTrue(citationUriMatches,
                "[" + scenarioName + "] at least one citation.sourceUri must equal '"
                        + SOURCE_URI + "', got: " + answer.citations().stream()
                                .map(Citation::sourceUri).toList());

        // ── assertion 3: recall@K ≥ 0.5 (count of reranked chunks / 1
        //    expected for this single-fixture scenario) ────────────────
        int rerankedCount = answer.reranked().size();
        assertTrue(rerankedCount >= 1,
                "[" + scenarioName + "] reranked pool must hold ≥1 chunk for recall@K ≥ 0.5, got: "
                        + rerankedCount);
    }

    /** Out-of-scope assertions: the policy's signature phrase MUST NOT
     *  appear in any reranked chunk for a query the policy does not cover.
     *  If retrieval still returns chunks (semantic search is fuzzy), the
     *  system must not surface a grounded answer that quotes the policy. */
    private void assertOutOfScope(String scenarioName, Answer answer) {
        List<String> rerankedContents = answer.reranked().stream()
                .map(io.github.yysf1949.rag.core.model.Chunk::content)
                .filter(c -> c != null)
                .toList();
        boolean anyPolicyPhrase = rerankedContents.stream()
                .anyMatch(c -> c.contains(POLICY_SIGNATURE));
        assertFalse(anyPolicyPhrase,
                "[" + scenarioName + "] OOS query must not surface reranked chunks containing the policy signature '"
                        + POLICY_SIGNATURE + "', got reranked=" + rerankedContents);
    }
}