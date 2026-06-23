package io.github.yysf1949.rag.llm.web;

import io.github.yysf1949.rag.llm.metrics.CostMeter;
import io.github.yysf1949.rag.llm.provider.ProviderRegistry;
import io.github.yysf1949.rag.llm.router.CircuitBreakerRegistry;
import io.github.yysf1949.rag.llm.router.LlmRouter;
import io.github.yysf1949.rag.llm.router.RoutingStrategy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Phase 38: LLM Router 管理 API — 供 UI 配置页面调用。
 */
@RestController
@RequestMapping("/api/admin/llm-router")
@Tag(name = "LLM Router Admin", description = "多 LLM 路由配置与管理 API")
public class LlmRouterController {

    private final ProviderRegistry providerRegistry;
    private final LlmRouter router;
    private final CircuitBreakerRegistry circuitBreakerRegistry;
    private final CostMeter costMeter;

    public LlmRouterController(ProviderRegistry providerRegistry, LlmRouter router,
                               CircuitBreakerRegistry circuitBreakerRegistry, CostMeter costMeter) {
        this.providerRegistry = providerRegistry;
        this.router = router;
        this.circuitBreakerRegistry = circuitBreakerRegistry;
        this.costMeter = costMeter;
    }

    /** 获取所有 provider 及其状态 */
    @GetMapping("/providers")
    @Operation(summary = "获取所有 LLM Provider 状态")
    public ResponseEntity<Map<String, ProviderStatus>> getProviders() {
        Map<String, ProviderStatus> result = new LinkedHashMap<>();
        for (var p : providerRegistry.listAll()) {
            var health = providerRegistry.healthCheck().get(p.providerId());
            var circuitState = circuitBreakerRegistry.getState(p.providerId());
            result.put(p.providerId(), new ProviderStatus(
                    p.displayName(),
                    p.defaultModel(),
                    p.isAvailable(),
                    health.inputPricePerKTokens(),
                    health.outputPricePerKTokens(),
                    circuitState.state(),
                    circuitState.failureCount(),
                    p.providerId()
            ));
        }
        return ResponseEntity.ok(result);
    }

    /** 获取路由建议 (测试路由引擎) */
    @GetMapping("/route")
    @Operation(summary = "根据 query 获取路由建议")
    public ResponseEntity<RouteAdvice> getRouteAdvice(
            @RequestParam String query,
            @RequestParam(required = false) String strategy,
            @RequestParam(required = false, defaultValue = "0") double maxCostCents) {
        LlmRouter.RoutingContext.Builder builder = LlmRouter.RoutingContext.builder()
                .queryText(query)
                .maxCostCents(maxCostCents);

        if (strategy != null && !strategy.isBlank()) {
            builder.strategy(RoutingStrategy.fromCode(strategy));
        }

        var ctx = builder.build();
        var selected = router.route(ctx);

        List<String> availableProviders = providerRegistry.listAvailable().stream()
                .map(p -> p.providerId())
                .toList();

        RouteAdvice advice = new RouteAdvice(
                selected.map(io.github.yysf1949.rag.llm.provider.LlmProvider::providerId).orElse(null),
                selected.map(io.github.yysf1949.rag.llm.provider.LlmProvider::defaultModel).orElse(null),
                ctx.strategy().code(),
                availableProviders,
                selected.map(p -> costMeter.estimateCost(p, ctx.estimatedInputTokens(), ctx.estimatedOutputTokens())).orElse(0.0)
        );
        return ResponseEntity.ok(advice);
    }

    /** 重置指定 provider 的熔断器 */
    @PostMapping("/circuit-breaker/{providerId}/reset")
    @Operation(summary = "重置熔断器")
    public ResponseEntity<Void> resetCircuitBreaker(@PathVariable String providerId) {
        circuitBreakerRegistry.reset(providerId);
        return ResponseEntity.ok().build();
    }

    /** 获取所有熔断状态 */
    @GetMapping("/circuit-breaker")
    @Operation(summary = "获取所有熔断状态")
    public ResponseEntity<Map<String, CircuitBreakerRegistry.CircuitState>> getCircuitBreakers() {
        return ResponseEntity.ok(circuitBreakerRegistry.getAllStates());
    }

    /** 获取成本统计 */
    @GetMapping("/costs")
    @Operation(summary = "获取成本统计")
    public ResponseEntity<Map<String, Double>> getCosts() {
        return ResponseEntity.ok(costMeter.getTotalCostByProvider());
    }

    public record ProviderStatus(
            String displayName,
            String defaultModel,
            boolean available,
            double inputPricePerKTokens,
            double outputPricePerKTokens,
            String circuitState,
            int failureCount,
            String providerId
    ) {}

    public record RouteAdvice(
            String selectedProvider,
            String selectedModel,
            String strategy,
            List<String> availableProviders,
            double estimatedCostCents
    ) {}
}
