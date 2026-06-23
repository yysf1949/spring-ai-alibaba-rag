package io.github.yysf1949.rag.agent.orchestration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Phase 15 Task 1: DeepSeek ChatClient Bean 配置.
 *
 * <h2>OpenAI-compatible 协议</h2>
 * <p>DeepSeek 走 OpenAI-compatible 协议, Spring AI 1.0.9 的 {@code spring-ai-openai-spring-boot-starter}
 * 通过 {@code spring.ai.openai.base-url=https://api.deepseek.com} 一行切到 DeepSeek, 不需要额外 adapter.</p>
 *
 * <h2>双层激活闸门</h2>
 * <ul>
 *   <li>{@link Profile @Profile("deepseek")} — 默认 dev/test profile 不激活, 避免启动报缺 key</li>
 *   <li>{@link ConditionalOnProperty @ConditionalOnProperty(api-key)} — 即便 profile 激活, 缺 key 也不创建 Bean
 *       (双层防御, 防止 {@code spring.ai.openai.api-key} 配错时启动失败)</li>
 * </ul>
 *
 * <h2>Bean 注入</h2>
 * <p>直接注入 Spring AI 自动配置的 {@link ChatClient.Builder}, 不重写 chat options —
 * 模型名 / temperature 等走 {@code application-deepseek.yml}.</p>
 */
@Configuration
@Profile("deepseek")
@ConditionalOnProperty(prefix = "spring.ai.openai", name = "api-key")
public class DeepSeekChatClientConfig {

    /**
     * 暴露 {@link ChatClient} 为 Spring Bean, 供 {@link ChatClientService} 注入.
     *
     * <p>不传任何 chat options, 让 yml 配置生效.</p>
     */
    @Bean
    public ChatClient deepSeekChatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}