package io.github.yysf1949.rag.llm.provider;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.data.Offset.offset;

/**
 * Phase 38: ProviderRegistry 测试。
 */
class ProviderRegistryTest {

    @Test
    void deepSeekProvider_hasCorrectPricing() {
        DeepSeekProvider provider = new DeepSeekProvider();
        assertThat(provider.providerId()).isEqualTo("deepseek");
        assertThat(provider.displayName()).isEqualTo("DeepSeek");
        assertThat(provider.inputPricePerKTokens()).isGreaterThan(0);
        assertThat(provider.outputPricePerKTokens()).isGreaterThan(0);
    }

    @Test
    void estimateCost_correctCalculation() {
        DeepSeekProvider provider = new DeepSeekProvider();
        // 1000 input + 3000 output
        double cost = provider.estimateCost(1000, 3000);
        double expected = 0.00027 * 1 + 0.00110 * 3; // $0.00357
        assertThat(cost).isCloseTo(expected, offset(0.00001));
    }

    @Test
    void allProviders_haveUniqueIds() {
        DeepSeekProvider deepseek = new DeepSeekProvider();
        SiliconFlowProvider siliconflow = new SiliconFlowProvider();
        OpenAiProvider openai = new OpenAiProvider();
        ClaudeProvider claude = new ClaudeProvider();
        QwenProvider qwen = new QwenProvider();
        assertThat(deepseek.providerId()).isNotEqualTo(siliconflow.providerId());
        assertThat(siliconflow.providerId()).isNotEqualTo(openai.providerId());
        assertThat(openai.providerId()).isNotEqualTo(claude.providerId());
        assertThat(claude.providerId()).isNotEqualTo(qwen.providerId());
    }
}