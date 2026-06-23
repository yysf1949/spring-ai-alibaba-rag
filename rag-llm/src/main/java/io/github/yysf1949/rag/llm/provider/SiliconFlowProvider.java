package io.github.yysf1949.rag.llm.provider;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Condition;
import org.springframework.context.annotation.ConditionContext;
import org.springframework.context.annotation.Conditional;
import org.springframework.core.type.AnnotatedTypeMetadata;
import org.springframework.stereotype.Component;

/**
 * SiliconFlow Provider — 硅基流动 (https://cloud.siliconflow.cn)。
 *
 * <h2>配置项</h2>
 * <ul>
 *   <li>{@code SILICONFLOW_API_KEY} — 环境变量</li>
 *   <li>{@code llm.provider.siliconflow.base-url} — 默认 {@code https://api.siliconflow.cn/v1}</li>
 *   <li>{@code llm.provider.siliconflow.model} — 默认 {@code deepseek-ai/DeepSeek-V3}</li>
 * </ul>
 */
@Component
@Conditional(SiliconFlowProvider.SiliconFlowEnabled.class)
public class SiliconFlowProvider implements LlmProvider {

    @Value("${spring.ai.openai.api-key:}")
    private String apiKey;

    @Value("${llm.provider.siliconflow.base-url:https://api.siliconflow.cn/v1}")
    private String baseUrl;

    @Value("${llm.provider.siliconflow.model:deepseek-ai/DeepSeek-V3}")
    private String modelName;

    @Override
    public String providerId() {
        return "siliconflow";
    }

    @Override
    public String displayName() {
        return "SiliconFlow";
    }

    @Override
    public String defaultModel() {
        return modelName;
    }

    // SiliconFlow pricing (CNY per 1M tokens, converted to USD per 1K)
    @Override
    public double inputPricePerKTokens() {
        return 0.00014; // ¥0.001 / 1M = ¥0.000001 / 1K ≈ $0.00014
    }

    @Override
    public double outputPricePerKTokens() {
        return 0.00028; // ¥0.002 / 1M ≈ $0.00028
    }

    @Override
    public boolean isAvailable() {
        return apiKey != null && !apiKey.isBlank();
    }

    @Override
    public ChatClient buildChatClient(ChatClient.Builder builder) {
        return builder.build();
    }

    static class SiliconFlowEnabled implements Condition {
        @Override
        public boolean matches(ConditionContext context, org.springframework.core.type.AnnotatedTypeMetadata metadata) {
            String key = context.getEnvironment().getProperty("spring.ai.openai.api-key");
            String sfEnabled = context.getEnvironment().getProperty("llm.provider.siliconflow.enabled");
            return (key != null && !key.isBlank()) || "true".equalsIgnoreCase(sfEnabled);
        }
    }
}
