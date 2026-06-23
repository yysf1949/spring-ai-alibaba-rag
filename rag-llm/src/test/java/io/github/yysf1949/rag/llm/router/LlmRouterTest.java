package io.github.yysf1949.rag.llm.router;

import io.github.yysf1949.rag.llm.provider.LlmProvider;
import io.github.yysf1949.rag.llm.provider.ProviderRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.util.Iterator;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 38: LlmRouter 测试。
 */
class LlmRouterTest {

    private LlmRouter router;
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        // Create test providers that are always available
        List<LlmProvider> providers = List.of(
                testProvider("deepseek", "DeepSeek", 0.00027, 0.00110),
                testProvider("siliconflow", "SiliconFlow", 0.00014, 0.00028),
                testProvider("openai", "OpenAI", 0.00015, 0.00060),
                testProvider("claude", "Claude (Anthropic)", 0.0030, 0.0150),
                testProvider("qwen", "Qwen (通义千问)", 0.000055, 0.000165)
        );

        ProviderRegistry providerRegistry = new ProviderRegistry(listObjectProvider(providers));
        circuitBreakerRegistry = new CircuitBreakerRegistry(
                meterObjectProvider(new SimpleMeterRegistry()), null, 3, 5, 30_000);
        router = new LlmRouter(providerRegistry, circuitBreakerRegistry);
    }

    @Test
    void route_withQuery_infersStrategy_correctly() {
        Optional<LlmProvider> result = router.routeByQuery("这个合同条款有什么法律风险？");
        assertThat(result).isPresent();
        // PRECISION strategy picks the most expensive provider (claude at $0.0030/1K input)
        assertThat(result.get().providerId()).isEqualTo("claude");
    }

    @Test
    void route_withCostConstraint_selectsAffordable() {
        LlmRouter.RoutingContext ctx = LlmRouter.RoutingContext.builder()
                .queryText("简单问题")
                .strategy(RoutingStrategy.COST_OPTIMIZED)
                .maxCostCents(10)
                .build();

        Optional<LlmProvider> result = router.route(ctx);
        assertThat(result).isPresent();
        // COST_OPTIMIZED picks cheapest (qwen at $0.000055/1K input)
        assertThat(result.get().providerId()).isEqualTo("qwen");
    }

    @Test
    void route_withBalancedStrategy_selectsBalanced() {
        LlmRouter.RoutingContext ctx = LlmRouter.RoutingContext.builder()
                .queryText("普通问题")
                .strategy(RoutingStrategy.BALANCED)
                .build();

        Optional<LlmProvider> result = router.route(ctx);
        assertThat(result).isPresent();
        // BALANCED = cheapest input, highest output among cheap ones
        assertThat(result.get().providerId()).isEqualTo("qwen");
    }

    @Test
    void circuitBreaker_opensAfterFailures() {
        for (int i = 0; i < 5; i++) {
            circuitBreakerRegistry.recordFailure("deepseek", "test failure " + i);
        }

        boolean isOpen = circuitBreakerRegistry.isCircuitOpen("deepseek");
        assertThat(isOpen).isTrue();
    }

    @Test
    void route_excludesCircuitOpenProvider() {
        for (int i = 0; i < 5; i++) {
            circuitBreakerRegistry.recordFailure("deepseek", "test");
        }

        Optional<LlmProvider> result = router.routeByQuery("test query");
        assertThat(result).isPresent();
        assertThat(result.get().providerId()).isNotEqualTo("deepseek");
    }

    @Test
    void routingContext_builder_worksCorrectly() {
        LlmRouter.RoutingContext ctx = LlmRouter.RoutingContext.builder()
                .strategy(RoutingStrategy.FAST)
                .queryText("快告诉我答案")
                .estimatedInputTokens(200)
                .estimatedOutputTokens(500)
                .maxCostCents(50)
                .tenantId("test-tenant")
                .requestId("req-123")
                .build();

        assertThat(ctx.strategy()).isEqualTo(RoutingStrategy.FAST);
        assertThat(ctx.queryText()).isEqualTo("快告诉我答案");
        assertThat(ctx.estimatedInputTokens()).isEqualTo(200);
        assertThat(ctx.estimatedOutputTokens()).isEqualTo(500);
        assertThat(ctx.maxCostCents()).isEqualTo(50);
        assertThat(ctx.tenantId()).isEqualTo("test-tenant");
        assertThat(ctx.requestId()).isEqualTo("req-123");
    }

    /** Create a test provider that's always available */
    private static LlmProvider testProvider(String id, String displayName,
                                            double inputPrice, double outputPrice) {
        return new LlmProvider() {
            @Override
            public String providerId() { return id; }

            @Override
            public String displayName() { return displayName; }

            @Override
            public String defaultModel() { return id + "-model"; }

            @Override
            public double inputPricePerKTokens() { return inputPrice; }

            @Override
            public double outputPricePerKTokens() { return outputPrice; }

            @Override
            public boolean isAvailable() { return true; }

            @Override
            public org.springframework.ai.chat.client.ChatClient buildChatClient(
                    org.springframework.ai.chat.client.ChatClient.Builder builder) {
                return builder.build();
            }
        };
    }

    /** Create a minimal ObjectProvider wrapping a list (supports forEach) */
    @SuppressWarnings("unchecked")
    private static <T> ObjectProvider<T> listObjectProvider(List<T> items) {
        return new ObjectProvider<T>() {
            @Override
            public T getObject(Object... args) { return items.get(0); }
            @Override
            public T getObject() { return items.get(0); }
            @Override
            public T getIfAvailable() { return items.isEmpty() ? null : items.get(0); }
            @Override
            public T getIfUnique() { return items.size() == 1 ? items.get(0) : null; }
            @Override
            public Stream<T> stream() { return items.stream(); }
            @Override
            public Stream<T> orderedStream() { return items.stream(); }
            @Override
            public Iterator<T> iterator() { return items.iterator(); }
            @Override
            public void forEach(Consumer<? super T> action) { items.forEach(action); }
        };
    }

    /** Create an ObjectProvider wrapping a single MeterRegistry */
    private static ObjectProvider<MeterRegistry> meterObjectProvider(MeterRegistry registry) {
        return listObjectProvider(List.of(registry));
    }
}