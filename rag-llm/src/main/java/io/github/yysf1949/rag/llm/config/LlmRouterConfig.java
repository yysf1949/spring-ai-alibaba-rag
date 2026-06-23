package io.github.yysf1949.rag.llm.config;

import io.github.yysf1949.rag.agent.governance.FailureClassificationRouter;
import io.github.yysf1949.rag.llm.metrics.CostMeter;
import io.github.yysf1949.rag.llm.provider.ProviderRegistry;
import io.github.yysf1949.rag.llm.router.CircuitBreakerRegistry;
import io.github.yysf1949.rag.llm.router.LlmRouter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 38: LLM Router 自动配置。
 *
 * <h2>自动创建的 Bean</h2>
 * <ul>
 *   <li>{@link LlmRouter} — 路由引擎</li>
 *   <li>{@link ProviderRegistry} — Provider 注册表</li>
 *   <li>{@link CircuitBreakerRegistry} — 熔断注册表</li>
 *   <li>{@link CostMeter} — 成本监控</li>
 * </ul>
 */
@Configuration
public class LlmRouterConfig {

    @Bean
    @ConditionalOnMissingBean
    public ProviderRegistry providerRegistry(ObjectProvider<io.github.yysf1949.rag.llm.provider.LlmProvider> providerProvider) {
        return new ProviderRegistry(providerProvider);
    }

    @Bean
    @ConditionalOnMissingBean
    public CircuitBreakerRegistry circuitBreakerRegistry(
            ObjectProvider<MeterRegistry> meterRegistryProvider,
            FailureClassificationRouter failureRouter) {
        return new CircuitBreakerRegistry(meterRegistryProvider, failureRouter);
    }

    @Bean
    @ConditionalOnMissingBean
    public LlmRouter llmRouter(ProviderRegistry providerRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        return new LlmRouter(providerRegistry, circuitBreakerRegistry);
    }

    @Bean
    @ConditionalOnMissingBean
    public CostMeter costMeter(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        return new CostMeter(meterRegistryProvider);
    }
}
