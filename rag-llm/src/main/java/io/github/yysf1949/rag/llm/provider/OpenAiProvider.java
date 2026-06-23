package io.github.yysf1949.rag.llm.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/**
 * OpenAI Provider — OpenAI API (GPT-4o / GPT-4o-mini)。
 */
@Component
@Conditional(OpenAiProvider.OpenAiEnabled.class)
public class OpenAiProvider implements LlmProvider {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${spring.ai.openai.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${spring.ai.openai.chat.options.model:gpt-4o-mini}")
    private String modelName;

    @Override
    public String providerId() {
        return "openai";
    }

    @Override
    public String displayName() {
        return "OpenAI";
    }

    @Override
    public String defaultModel() {
        return modelName;
    }

    @Override
    public double inputPricePerKTokens() {
        return 0.00015; // GPT-4o-mini: $0.15 / 1M
    }

    @Override
    public double outputPricePerKTokens() {
        return 0.00060; // GPT-4o-mini: $0.60 / 1M
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatClient buildChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    static class OpenAiEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("spring.ai.openai.api-key");
            String openaiEnabled = context.getEnvironment().getProperty("llm.provider.openai.enabled");
            return (key != null && !key.isBlank()) || "true".equalsIgnoreCase(openaiEnabled);
        }
    }
}
