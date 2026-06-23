package io.github.yysf1949.rag.llm.router;

import io.github.yysf1949.rag.llm.metrics.CostMeter;
import io.github.yysf1949.rag.llm.provider.LlmProvider;
import io.github.yysf1949.rag.llm.provider.ProviderRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.model.ChatResponse;

import java.util.Optional;

/**
 * Phase 38: LLM 路由 Advisor — Spring AI ChatClient 中间件。
 *
 * <h2>工作流程</h2>
 * <ol>
 *   <li>从请求 context 中提取路由元数据 (策略、请求 ID 等)</li>
 *   <li>调用 {@link LlmRouter} 获取路由建议 (仅记录决策日志)</li>
 *   <li>执行原始 ChatClient 调用 (模型选择由 ChatClient 自身管理)</li>
 *   <li>调用完成后记录成本和 token 使用量到 {@link CostMeter}</li>
 * </ol>
 */
public class LlmRoutingAdvisor implements CallAdvisor {

    private static final Logger log = LoggerFactory.getLogger(LlmRoutingAdvisor.class);

    /** 路由上下文属性键 (存储在 ChatClientRequest context map 中) */
    public static final String ATTR_ROUTING_STRATEGY = "llm.routing.strategy";
    public static final String ATTR_ROUTING_MAX_COST_CENTS = "llm.routing.max_cost_cents";
    public static final String ATTR_ROUTING_TENANT_ID = "llm.routing.tenant_id";
    public static final String ATTR_ROUTING_REQUEST_ID = "llm.routing.request_id";

    private final LlmRouter router;
    private final CostMeter costMeter;
    private final ProviderRegistry providerRegistry;

    public LlmRoutingAdvisor(LlmRouter router, CostMeter costMeter, ProviderRegistry providerRegistry) {
        this.router = router;
        this.costMeter = costMeter;
        this.providerRegistry = providerRegistry;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // 1. 从 context 提取路由元数据
        String requestId = extractFromContext(request, ATTR_ROUTING_REQUEST_ID, "unknown");
        String strategyCode = extractFromContext(request, ATTR_ROUTING_STRATEGY, "balanced");
        String queryText = request.prompt().getUserMessage() != null
                ? request.prompt().getUserMessage().getText() : "";

        // 2. 获取路由建议 (仅做决策记录)
        RoutingStrategy strategy = RoutingStrategy.fromCode(strategyCode);
        LlmRouter.RoutingContext routingCtx = LlmRouter.RoutingContext.builder()
                .strategy(strategy)
                .queryText(queryText)
                .requestId(requestId)
                .build();

        Optional<LlmProvider> selected = router.route(routingCtx);
        String selectedId = selected.map(LlmProvider::providerId).orElse("none");

        log.info("[Router] request={} strategy={} selected={} query_length={}",
                requestId, strategyCode, selectedId,
                queryText != null ? queryText.length() : 0);

        // 3. 执行调用 (模型切换由 ChatClient 自身处理，Advisor 仅记录)
        long start = System.currentTimeMillis();
        try {
            ChatClientResponse response = chain.nextCall(request);
            long elapsed = System.currentTimeMillis() - start;

            // 4. 记录成功: 成本和 token
            if (selected.isPresent()) {
                LlmProvider provider = selected.get();
                ChatResponse chatResponse = response.chatResponse();
                if (chatResponse != null && chatResponse.getResult() != null) {
                    LlmProvider.TokenUsage usage = provider.extractTokenUsage(chatResponse);
                    double costCents = costMeter.estimateCost(provider, usage.inputTokens(), usage.outputTokens());
                    costMeter.recordCall(provider.providerId(), provider.defaultModel(),
                            usage.inputTokens(), usage.outputTokens(), costCents);
                    log.info("[Router] request={} cost={} cents tokens_in={} tokens_out={} elapsed={}ms",
                            requestId, costCents, usage.inputTokens(), usage.outputTokens(), elapsed);
                } else {
                    log.info("[Router] request={} no usage metadata in response (elapsed={}ms)", requestId, elapsed);
                }

                router.recordRoutingDecision(requestId, provider, routingCtx,
                        "success elapsed=" + elapsed + "ms");
            }

            return response;

        } catch (Exception e) {
            // 5. 记录失败
            if (selected.isPresent()) {
                router.recordRoutingDecision(requestId, selected.get(), routingCtx,
                        "failure: " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            throw e;
        }
    }

    @Override
    public String getName() {
        return "llm-routing-advisor";
    }

    @Override
    public int getOrder() {
        return 0;
    }

    private String extractFromContext(ChatClientRequest request, String key, String defaultValue) {
        Object val = request.context().get(key);
        return val != null ? val.toString() : defaultValue;
    }
}