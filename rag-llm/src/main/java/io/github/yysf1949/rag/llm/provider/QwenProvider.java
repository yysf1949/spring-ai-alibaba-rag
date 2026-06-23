package io.github.yysf1949.rag.llm.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/**
 * Qwen (通义千问) Provider — 阿里云 DashScope API。
 */
@Component
@Conditional(QwenProvider.QwenEnabled.class)
public class QwenProvider implements LlmProvider {

    @Value("${llm.provider.qwen.api-key:}")
    private String apiKey;

    @Value("${llm.provider.qwen.base-url:https://dashscope.aliyuncs.com/compatible-mode/v1}")
    private String baseUrl;

    @Value("${llm.provider.qwen.model:qwen-plus}")
    private String modelName;

    @Override
    public String providerId() {
        return "qwen";
    }

    @Override
    public String displayName() {
        return "Qwen (通义千问)";
    }

    @Override
    public String defaultModel() {
        return modelName;
    }

    // Qwen-plus pricing (CNY per 1K tokens, converted to USD)
    @Override
    public double inputPricePerKTokens() {
        return 0.000055; // ¥0.0004 / 1K ≈ $0.000055
    }

    @Override
    public double outputPricePerKTokens() {
        return 0.000165; // ¥0.0012 / 1K ≈ $0.000165
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatClient buildChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    static class QwenEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("llm.provider.qwen.api-key");
            return key != null && !key.isBlank();
        }
    }
}
