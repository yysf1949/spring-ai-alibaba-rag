package io.github.yysf1949.rag.llm.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/**
 * Claude (Anthropic) Provider — Claude 3.5 Sonnet / 3 Opus。
 */
@Component
@Conditional(ClaudeProvider.ClaudeEnabled.class)
public class ClaudeProvider implements LlmProvider {

    @Value("${llm.provider.claude.api-key:}")
    private String apiKey;

    @Value("${llm.provider.claude.model:claude-3-5-sonnet-20241022}")
    private String modelName;

    @Override
    public String providerId() {
        return "claude";
    }

    @Override
    public String displayName() {
        return "Claude (Anthropic)";
    }

    @Override
    public String defaultModel() {
        return modelName;
    }

    // Claude 3.5 Sonnet pricing (USD per 1K tokens)
    @Override
    public double inputPricePerKTokens() {
        return 0.0030; // $3.00 / 1M
    }

    @Override
    public double outputPricePerKTokens() {
        return 0.0150; // $15.00 / 1M
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatClient buildChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    static class ClaudeEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("llm.provider.claude.api-key");
            return key != null && !key.isBlank();
        }
    }
}
