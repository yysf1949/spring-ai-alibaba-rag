package io.github.yysf1949.rag.llm.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Conditional;
import org.springframework.stereotype.Component;

/**
 * DeepSeek Provider — 基于 OpenAI-compatible API。
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code spring.ai.openai.api-key} — DeepSeek API key (环境变量 {@code DEEPSEEK_API_KEY})</li>
 *   <li>{@code spring.ai.openai.base-url} — 默认 {@code https://api.deepseek.com}</li>
 *   <li>{@code spring.ai.openai.chat.options.model} — 默认 {@code deepseek-chat}</li>
 * </ul>
 */
@Component
@Conditional(DeepSeekProvider.DeepSeekEnabled.class)
public class DeepSeekProvider implements LlmProvider {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:deepseek-chat}")
    private String modelName;

    @Value("${spring.ai.openai.chat.options.temperature:0.7}")
    private double temperature;

    @Override
    public String providerId() {
        return "deepseek";
    }

    @Override
    public String displayName() {
        return "DeepSeek";
    }

    @Override
    public String defaultModel() {
        return modelName;
    }

    // DeepSeek pricing (2026-06, USD per 1K tokens)
    @Override
    public double inputPricePerKTokens() {
        return 0.00027; // ¥0.002 / 1K ≈ $0.00027
    }

    @Override
    public double outputPricePerKTokens() {
        return 0.00110; // ¥0.008 / 1K ≈ $0.00110
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatClient buildChatClient(ChatClient.Builder builder) {
        return builder
                .defaultSystem("You are a helpful AI assistant.")
                .defaultAdvisors()
                .build();
    }

    /** Auto-configuration condition */
    static class DeepSeekEnabled implements org.springframework.context.annotation.Condition {
        @Override
        public boolean matches(org.springframework.context.annotation.ConditionContext context,
                org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("spring.ai.openai.api-key");
            return key != null && !key.isBlank();
        }
    }
}
