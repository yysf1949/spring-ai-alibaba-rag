package io.github.yysf1949.rag.pipeline.qa;

import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.KbVersion;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.AnswerCache;
import io.github.yysf1949.rag.core.port.EmbeddingCache;
import io.github.yysf1949.rag.core.port.EmbeddingGateway;
import io.github.yysf1949.rag.core.port.HotQuestionProvider;
import io.github.yysf1949.rag.core.port.LlmService;
import io.github.yysf1949.rag.core.port.RerankService;
import io.github.yysf1949.rag.core.port.RewriteService;
import io.github.yysf1949.rag.core.port.VectorStore;
import io.github.yysf1949.rag.pipeline.context.ContextAssembler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Verifies the Resilience4j rate limiter wiring on {@link QAServiceImpl#answer(Query)}.
 *
 * <p>Builds a {@link RateLimiterRegistry} with a deliberately tiny budget
 * (2 permits per 10 seconds, fail-fast) and a {@link QAServiceImpl} whose
 * collaborators are all lenient Mockito mocks so any throw from
 * {@code answer()} is provably attributable to the limiter, not a real
 * pipeline failure.</p>
 *
 * <p>Spec: §13 (resilience / rate limiting).</p>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class QAServiceImplRateLimiterTest {

    /** Tight budget: 2 permits per 10 seconds, fail-fast (timeout 0). */
    private static final RateLimiterConfig TIGHT = RateLimiterConfig.custom()
            .limitForPeriod(2)
            .limitRefreshPeriod(Duration.ofSeconds(10))
            .timeoutDuration(Duration.ZERO)
            .build();

    @Mock private RewriteService rewriter;
    @Mock private AnswerCache answerCache;
    @Mock private EmbeddingCache embeddingCache;
    @Mock private EmbeddingGateway embeddingGateway;
    @Mock private VectorStore vectorStore;
    @Mock private RerankService reranker;
    @Mock private ContextAssembler contextAssembler;
    @Mock private LlmService llm;
    @Mock private HotQuestionProvider hotQuestions;

    private QAServiceImpl qa;
    private Query q;

    @BeforeEach
    void setUp() {
        RateLimiterRegistry registry = RateLimiterRegistry.of(TIGHT);
        // Eagerly create the "qa" instance so QAServiceImpl picks it up.
        registry.rateLimiter("qa");

        // Collaborator stubs — return the minimal "happy path" so the chain
        // doesn't fail for reasons other than the rate limiter.
        lenient().when(rewriter.rewrite(anyString(), anyString()))
                .thenReturn(new RewriteService.RewriteResult("rewritten", 1.0, false));
        lenient().when(answerCache.get(anyString(), anyString())).thenReturn(Optional.empty());
        lenient().when(embeddingCache.get(anyString())).thenReturn(null);
        lenient().when(embeddingGateway.embedBatch(any()))
                .thenReturn(List.of(new float[]{0.1f, 0.2f, 0.3f}));
        lenient().when(embeddingGateway.dimension()).thenReturn(3);
        lenient().when(vectorStore.search(any(), anyString(), anyString(), anyLong(),
                any(), any(), anyInt()))
                .thenReturn(List.of());
        lenient().when(reranker.rerank(anyString(), any(), anyInt())).thenReturn(List.of());
        lenient().when(reranker.rerankWithScores(anyString(), any(), anyInt())).thenReturn(List.of());
        lenient().when(contextAssembler.assemble(any(), anyString(), anyInt()))
                .thenReturn(new ContextAssembler.AssembledPrompt("ctx", List.of(), 0, 0, false));
        lenient().when(llm.generateAnswer(anyString(), anyString())).thenReturn("stub-answer");
        lenient().when(llm.modelId()).thenReturn("stub");
        lenient().when(hotQuestions.recent(anyString(), anyInt())).thenReturn(List.of());

        qa = new QAServiceImpl(rewriter, answerCache, embeddingCache, embeddingGateway,
                vectorStore, reranker, contextAssembler, llm, hotQuestions,
                new SimpleMeterRegistry(), registry);

        q = new Query("t1", "u1", "s1", "hello",
                Set.of(), 5, new KbVersion("t1", "k1", 1L));
    }

    @Test
    void allowsRequestsUpToLimitThenThrowsRequestNotPermitted() {
        // First two calls fit the budget (limitForPeriod=2).
        for (int i = 0; i < 2; i++) {
            int call = i;
            assertDoesNotThrow(() -> qa.answer(q),
                    "call #" + call + " should be within the rate-limit budget");
        }

        // Third call exceeds the budget — limiter fails fast (timeout 0) → RequestNotPermitted.
        RequestNotPermitted ex = assertThrows(RequestNotPermitted.class, () -> qa.answer(q));
        assertTrue(ex.getMessage().toLowerCase().contains("rate")
                        || ex.getMessage().toLowerCase().contains("permit"),
                "exception should mention rate/permit: " + ex.getMessage());
    }
}
