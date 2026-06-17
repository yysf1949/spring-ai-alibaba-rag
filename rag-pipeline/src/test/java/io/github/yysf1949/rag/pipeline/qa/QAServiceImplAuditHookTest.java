package io.github.yysf1949.rag.pipeline.qa;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.KbVersion;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.LlmAuditHook;
import io.github.yysf1949.rag.core.port.LlmService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the {@link LlmAuditHook} integration in {@link QAServiceImpl}
 * — design spec §21 (LLM-call audit trail).
 *
 * <p>We use a small fake hook (NOT Mockito) to capture the (model, prompt,
 * response) triple, and the existing fakes in {@link QAServiceImplTest}
 * (same package) to wire the rest of the pipeline. The test asserts:</p>
 * <ul>
 *   <li>The hook is called exactly once per LLM call.</li>
 *   <li>It receives the correct tenant, queryHash, modelId, prompt, completion.</li>
 *   <li>Outcome is "SUCCESS" on the happy path.</li>
 *   <li>Outcome is "DEGRADED" when the LLM throws and the pipeline falls
 *       back to FALLBACK_RULE.</li>
 *   <li>A throwing hook does NOT abort the LLM call path (the never-throw
 *       contract from {@link LlmAuditHook}).</li>
 * </ul>
 */
class QAServiceImplAuditHookTest {

    /** Wire a QAServiceImpl that uses the same fakes as the main test. */
    private static QAServiceImpl newQa(QAServiceImplTest.StubVectorStore vs,
                                       LlmService llm,
                                       LlmAuditHook hook) {
        var rewriter = new io.github.yysf1949.rag.pipeline.rewrite.RuleBasedQueryRewriter(
                io.github.yysf1949.rag.pipeline.rewrite.DefaultChineseSynonymTable.create());
        QAServiceImplTest.StubRerank rerank = new QAServiceImplTest.StubRerank();
        QAServiceImplTest.StubAnswerCache ansCache = new QAServiceImplTest.StubAnswerCache();
        QAServiceImplTest.StubEmbedCache embCache = new QAServiceImplTest.StubEmbedCache();
        var embGw = new io.github.yysf1949.rag.embedding.stub.StubEmbeddingGateway();
        var asm = new io.github.yysf1949.rag.pipeline.context.ContextAssembler();
        QAServiceImplTest.StubHot hq = new QAServiceImplTest.StubHot();
        return new QAServiceImpl(rewriter, ansCache, embCache, embGw, vs, rerank, asm, llm, hq,
                new SimpleMeterRegistry(), null, hook);
    }

    private static Chunk chunk(String id, String section, String content) {
        return new Chunk(id, "tenant-A", "kb-1", "doc-1", "1",
                "退款规则", section, content,
                new HashSet<>(Set.of()), ChunkStatus.ACTIVE, Instant.now(),
                "https://docs.example.com/refund", new float[0], null);
    }

    private static Query query(String rawText) {
        return new Query("tenant-A", "user-1", "sess-1", rawText,
                new HashSet<>(Set.of("ROLE_USER")), 20,
                new KbVersion("tenant-A", "kb-1", 1));
    }

    @Test
    void successPathInvokesHookWithModelAndCompletion() {
        CapturingHook hook = new CapturingHook();
        QAServiceImplTest.StubVectorStore vs = new QAServiceImplTest.StubVectorStore();
        vs.toReturn = List.of(chunk("c1", "运费条款", "运费退还"));
        QAServiceImplTest.StubLlm llm = new QAServiceImplTest.StubLlm();
        llm.toReturn = "the answer is 42";
        QAServiceImpl qa = newQa(vs, llm, hook);

        Answer a = qa.answer(query("退款多少天到账?"));

        assertEquals(AnswerSource.LLM, a.source());
        assertEquals(1, hook.calls.size(), "hook must be invoked exactly once on success");
        LlmAuditHookCall call = hook.calls.get(0);
        assertEquals("tenant-A", call.tenantId);
        assertEquals("user-1", call.userId);
        assertEquals("sess-1", call.sessionId);
        assertNotNull(call.queryHash);
        assertTrue(call.queryHash.length() == 64, "queryHash is SHA-256 hex (64 chars)");
        assertEquals("stub-llm", call.modelId);
        assertEquals("qa-default", call.promptTemplate);
        assertNotNull(call.promptBody);
        assertTrue(call.promptBody.contains("退款"), "prompt must include the user query");
        assertEquals("the answer is 42", call.completion);
        assertTrue(call.latencyMs >= 0, "latencyMs is recorded");
        assertEquals("SUCCESS", call.outcome);
    }

    @Test
    void degradedPathInvokesHookWithDegradedOutcome() {
        CapturingHook hook = new CapturingHook();
        QAServiceImplTest.StubVectorStore vs = new QAServiceImplTest.StubVectorStore();
        vs.toReturn = List.of(chunk("c1", "运费条款", "运费退还"));
        QAServiceImplTest.StubLlm llm = new QAServiceImplTest.StubLlm();
        llm.toThrow = new io.github.yysf1949.rag.core.exception.LlmUnavailableException(
                "rate limit exceeded");
        QAServiceImpl qa = newQa(vs, llm, hook);

        Answer a = qa.answer(query("运费多少"));

        assertEquals(AnswerSource.FALLBACK_RULE, a.source(),
                "fallback must trigger when LLM throws LlmUnavailableException");
        assertEquals(1, hook.calls.size());
        LlmAuditHookCall call = hook.calls.get(0);
        assertEquals("DEGRADED", call.outcome);
        assertTrue(call.completion.contains("rate limit"),
                "audit completion should contain the failure reason");
    }

    @Test
    void throwingHookDoesNotAbortTheCall() {
        ThrowingHook hook = new ThrowingHook();
        QAServiceImplTest.StubVectorStore vs = new QAServiceImplTest.StubVectorStore();
        vs.toReturn = List.of(chunk("c1", "运费条款", "运费退还"));
        QAServiceImplTest.StubLlm llm = new QAServiceImplTest.StubLlm();
        llm.toReturn = "ok";
        QAServiceImpl qa = newQa(vs, llm, hook);

        // MUST NOT throw — the LLM call path is bulletproof against a
        // misbehaving audit hook.
        Answer a = qa.answer(query("运费多少"));
        assertEquals(AnswerSource.LLM, a.source());
        assertEquals("ok", a.finalText());
    }

    // ── fakes ──────────────────────────────────────────────────────────

    /** Minimal fake that captures each {@code onLlmCall} invocation. */
    static final class CapturingHook implements LlmAuditHook {
        final List<LlmAuditHookCall> calls = new ArrayList<>();
        @Override
        public void onLlmCall(String tenantId, String userId, String sessionId,
                              String queryHash, String modelId, String promptTemplate,
                              String promptBody, String completion, long latencyMs,
                              String outcome) {
            calls.add(new LlmAuditHookCall(tenantId, userId, sessionId, queryHash, modelId,
                    promptTemplate, promptBody, completion, latencyMs, outcome));
        }
    }

    /** Hook that always throws — used to verify the never-throw contract. */
    static final class ThrowingHook implements LlmAuditHook {
        @Override
        public void onLlmCall(String tenantId, String userId, String sessionId,
                              String queryHash, String modelId, String promptTemplate,
                              String promptBody, String completion, long latencyMs,
                              String outcome) {
            throw new RuntimeException("boom — hook misbehaving");
        }
    }

    /** Value type for one captured call. */
    record LlmAuditHookCall(
            String tenantId, String userId, String sessionId, String queryHash,
            String modelId, String promptTemplate, String promptBody, String completion,
            long latencyMs, String outcome) {}
}
