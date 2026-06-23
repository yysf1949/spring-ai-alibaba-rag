package io.github.yysf1949.rag.llm.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.ModelOptionsUtils;
import org.springframework.ai.retry.RetryUtils;
import org.springframework.retry.support.RetryTemplateBuilder;

import java.util.List;
import java.util.function.Function;

/**
 * Phase 38: LLM Provider 接口 — 统一 5 家提供商的调用契约。
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>每个 provider 一个实现类，通过 {@code @Configuration} + {@code @ConditionalOnProperty} 按需激活</li>
 *   <li>所有 provider 共享同一个 {@link ChatClient} 构造逻辑，差异仅在于 base-url / api-key / model-name</li>
 *   <li>返回的 {@link ProviderInfo} 包含定价信息，供 {@link LlmRouter} 做成本决策</li>
 * </ul>
 */
public interface LlmProvider {

    /** 提供商标识符 (用于路由决策和指标标签) */
    String providerId();

    /** 提供商人类可读名称 */
    String displayName();

    /** 默认模型名 */
    String defaultModel();

    /** 输入 token 单价 (美元/1K tokens) */
    double inputPricePerKTokens();

    /** 输出 token 单价 (美元/1K tokens) */
    double outputPricePerKTokens();

    /** 是否当前可用 (健康检查) */
    boolean isAvailable();

    /** 构建该 provider 专用的 ChatClient */
    ChatClient buildChatClient(ChatClient.Builder builder);

    /**
     * 计算一次调用的预估成本 (美元)
     * @param inputTokens 输入 token 数
     * @param outputTokens 输出 token 数
     */
    default double estimateCost(int inputTokens, int outputTokens) {
        return (inputTokens / 1000.0) * inputPricePerKTokens()
             + (outputTokens / 1000.0) * outputPricePerKTokens();
    }

    /**
     * 从 ChatResponse 中提取 token 使用情况
     */
    default TokenUsage extractTokenUsage(ChatResponse response) {
        if (response == null) return TokenUsage.empty();
        var metadata = response.getMetadata();
        if (metadata == null) return TokenUsage.empty();
        var usage = metadata.getUsage();
        if (usage == null) return TokenUsage.empty();
        return new TokenUsage(
                usage.getPromptTokens() != null ? usage.getPromptTokens() : 0,
                usage.getCompletionTokens() != null ? usage.getCompletionTokens() : 0,
                usage.getTotalTokens() != null ? usage.getTotalTokens() : 0
        );
    }

    record TokenUsage(int inputTokens, int outputTokens, int totalTokens) {
        public static TokenUsage empty() { return new TokenUsage(0, 0, 0); }
        public int inputTokens() { return inputTokens; }
        public int outputTokens() { return outputTokens; }
        public int totalTokens() { return totalTokens; }
    }
}
