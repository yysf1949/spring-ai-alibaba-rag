package io.github.yysf1949.rag.llm.router;

import io.github.yysf1949.rag.llm.provider.LlmProvider;
import io.github.yysf1949.rag.llm.provider.ProviderRegistry;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Phase 38: LLM 智能路由引擎 — 根据 query 类型 / 成本约束 / SLA 要求动态选择最优 Provider。
 *
 * <h2>路由决策流程</h2>
 * <ol>
 *   <li>解析路由规则 (从 RoutingContext 或默认规则)</li>
 *   <li>过滤可用 provider (isAvailable = true)</li>
 *   <li>按策略排序: PRECISION→质量优先, FAST→延迟优先, COST_OPTIMIZED→成本优先</li>
 *   <li>返回最优 provider</li>
 * </ol>
 *
 * <h2>熔断联动</h2>
 * <p>当选中的 provider 调用失败时，通过 {@link CircuitBreakerRegistry} 记录失败计数，
 * 触发自动降级到备用 provider。</p>
 */
public class LlmRouter {

    private final ProviderRegistry providerRegistry;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public LlmRouter(ProviderRegistry providerRegistry, CircuitBreakerRegistry circuitBreakerRegistry) {
        this.providerRegistry = providerRegistry;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    /**
     * 根据路由上下文选择最优 provider。
     *
     * @param ctx 路由上下文 (含策略、成本上限、SLA 要求)
     * @return 选中的 provider，如果没有可用 provider 则返回空
     */
    public Optional<LlmProvider> route(RoutingContext ctx) {
        List<LlmProvider> available = providerRegistry.listAvailable();
        if (available.isEmpty()) {
            return Optional.empty();
        }

        // 如果当前 provider 被熔断，排除它
        List<LlmProvider> healthy = available.stream()
                .filter(p -> !circuitBreakerRegistry.isCircuitOpen(p.providerId()))
                .toList();

        if (healthy.isEmpty()) {
            // 所有 provider 都被熔断，尝试强制使用成本最低的
            healthy = available.stream()
                    .sorted(Comparator.comparingDouble(LlmProvider::inputPricePerKTokens))
                    .toList();
        }

        // 按策略排序
        List<LlmProvider> sorted = sort_by_strategy(healthy, ctx.strategy());

        // 检查成本上限
        if (ctx.maxCostCents() > 0) {
            for (LlmProvider p : sorted) {
                double estCost = estimateMaxCost(p, ctx.estimatedInputTokens(), ctx.estimatedOutputTokens());
                if (estCost <= ctx.maxCostCents()) {
                    return Optional.of(p);
                }
            }
        }

        return sorted.isEmpty() ? Optional.empty() : Optional.of(sorted.get(0));
    }

    /**
     * 快捷方法: 根据 query 文本自动推断策略并路由。
     */
    public Optional<LlmProvider> routeByQuery(String query) {
        RoutingStrategy strategy = inferStrategy(query);
        RoutingContext ctx = RoutingContext.builder()
                .strategy(strategy)
                .queryText(query)
                .build();
        return route(ctx);
    }

    /**
     * 根据 query 内容自动推断路由策略。
     */
    private RoutingStrategy inferStrategy(String query) {
        if (query == null) return RoutingStrategy.BALANCED;
        String q = query.toLowerCase();

        // 高精度关键词
        if (q.contains("法律") || q.contains("医疗") || q.contains("诊断") ||
            q.contains("金融") || q.contains("合规") || q.contains("审计") ||
            q.contains("合同") || q.contains("诉讼")) {
            return RoutingStrategy.PRECISION;
        }

        // 长文本关键词
        if (q.contains("总结") || q.contains("分析") || q.contains("全文") ||
            q.contains("长文") || q.contains("文档") || q.contains("报告")) {
            return RoutingStrategy.LONG_CONTEXT;
        }

        // 快速响应关键词
        if (q.contains("快") || q.contains("马上") || q.contains("立刻") ||
            q.contains("实时") || q.contains("现在")) {
            return RoutingStrategy.FAST;
        }

        return RoutingStrategy.BALANCED;
    }

    private List<LlmProvider> sort_by_strategy(List<LlmProvider> providers, RoutingStrategy strategy) {
        return switch (strategy) {
            case PRECISION -> providers.stream()
                    .sorted(Comparator
                            .comparingDouble((LlmProvider p) -> -p.outputPricePerKTokens())
                            .thenComparingDouble(p -> -p.inputPricePerKTokens()))
                    .toList();

            case FAST -> providers.stream()
                    .sorted(Comparator.comparingDouble(LlmProvider::inputPricePerKTokens))
                    .toList();

            case COST_OPTIMIZED -> providers.stream()
                    .sorted(Comparator
                            .comparingDouble(LlmProvider::inputPricePerKTokens)
                            .thenComparingDouble(LlmProvider::outputPricePerKTokens))
                    .toList();

            case LONG_CONTEXT -> providers.stream()
                    .sorted(Comparator.comparingDouble((LlmProvider p) -> -p.outputPricePerKTokens()))
                    .toList();

            case BALANCED -> providers.stream()
                    .sorted(Comparator
                            .comparingDouble(LlmProvider::inputPricePerKTokens)
                            .thenComparingDouble(p -> -p.outputPricePerKTokens()))
                    .toList();
        };
    }

    private double estimateMaxCost(LlmProvider provider, int inputTokens, int outputTokens) {
        // 预估最大成本: 假设输出 tokens 可能达到输入的 3 倍
        int maxOutput = Math.max(outputTokens, inputTokens * 3);
        return provider.estimateCost(inputTokens, maxOutput) * 100; // 转换为 cents
    }

    /**
     * 记录一次路由决策 (用于审计)
     */
    public void recordRoutingDecision(String requestId, LlmProvider selected, RoutingContext ctx, String reason) {
        // 审计日志会在后续集成到 AuditChannel
        System.out.printf("[Router] request=%s selected=%s strategy=%s reason=%s%n",
                requestId, selected != null ? selected.providerId() : "none",
                ctx.strategy(), reason);
    }

    /**
     * 路由上下文 — 不可变数据类
     */
    public static class RoutingContext {
        private final RoutingStrategy strategy;
        private final String queryText;
        private final int estimatedInputTokens;
        private final int estimatedOutputTokens;
        private final double maxCostCents; // 0 = 无限制
        private final String tenantId;
        private final String requestId;

        private RoutingContext(Builder builder) {
            this.strategy = builder.strategy;
            this.queryText = builder.queryText;
            this.estimatedInputTokens = builder.estimatedInputTokens;
            this.estimatedOutputTokens = builder.estimatedOutputTokens;
            this.maxCostCents = builder.maxCostCents;
            this.tenantId = builder.tenantId;
            this.requestId = builder.requestId;
        }

        public static Builder builder() { return new Builder(); }

        public RoutingStrategy strategy() { return strategy; }
        public String queryText() { return queryText; }
        public int estimatedInputTokens() { return estimatedInputTokens; }
        public int estimatedOutputTokens() { return estimatedOutputTokens; }
        public double maxCostCents() { return maxCostCents; }
        public String tenantId() { return tenantId; }
        public String requestId() { return requestId; }

        public static class Builder {
            private RoutingStrategy strategy = RoutingStrategy.BALANCED;
            private String queryText;
            private int estimatedInputTokens = 100;
            private int estimatedOutputTokens = 300;
            private double maxCostCents = 0;
            private String tenantId;
            private String requestId;

            public Builder strategy(RoutingStrategy s) { this.strategy = s; return this; }
            public Builder queryText(String q) { this.queryText = q; return this; }
            public Builder estimatedInputTokens(int n) { this.estimatedInputTokens = n; return this; }
            public Builder estimatedOutputTokens(int n) { this.estimatedOutputTokens = n; return this; }
            public Builder maxCostCents(double c) { this.maxCostCents = c; return this; }
            public Builder tenantId(String t) { this.tenantId = t; return this; }
            public Builder requestId(String r) { this.requestId = r; return this; }

            public RoutingContext build() {
                return new RoutingContext(this);
            }
        }
    }
}
