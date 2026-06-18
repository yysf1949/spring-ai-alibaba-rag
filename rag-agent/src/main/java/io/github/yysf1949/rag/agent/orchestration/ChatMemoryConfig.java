package io.github.yysf1949.rag.agent.orchestration;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Phase 16 Task 1: 多轮对话 ChatMemory 装配.
 *
 * <h2>与既有链路的关系</h2>
 * <p>本配置是 ChatClientService 的"插件" — 仅当 ChatClient Bean 存在时 (即 deepseek profile 激活) 才装配.
 * 单测场景无 ChatClient Bean → 本配置整体不生效 → 不影响 Phase 14/15 ship 的 213 测试基线.</p>
 *
 * <h2>为什么 InMemory + MessageWindowChatMemory (M=20)</h2>
 * <ul>
 *   <li>Demo / 单机够用: 进程内 Map, 零依赖, 重启清空</li>
 *   <li>M=20 消息窗口: 够 5-7 轮对话, 超出则 LRU 淘汰早期 context, 防止 prompt 无限增长</li>
 *   <li>Redis / JDBC 持久化推 Phase 18: 那时需要 tenantId 隔离 + TTL, 单独 Phase</li>
 * </ul>
 *
 * <h2>为什么把 MessageChatMemoryAdvisor 也作为 Bean</h2>
 * <p>Phase 16 ChatClientService.chatWithMemory / stream 需要调用
 * {@code .advisors(a -> a.param(MessageChatMemoryAdvisor.CONVERSATION_ID, conversationId))} —
 * Advisor 自身是无状态的, 拿 ChatMemory 注入, conversationId 由 Prompt 运行时决定.
 * 暴露成 Bean 方便单测用 {@code @MockBean} 替换.</p>
 */
@Configuration
@ConditionalOnBean(ChatClient.class)
public class ChatMemoryConfig {

    /**
     * InMemory 仓库 — 单 JVM 内 Map<conversationId, List<Message>>.
     * 生产替换见 {@code rag-redis} 模块 (Phase 18).
     */
    @Bean
    public ChatMemoryRepository chatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    /**
     * 滑动窗口 ChatMemory — 最多保留最近 20 条消息.
     */
    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    /**
     * Memory Advisor — 自动把 conversationId 对应的历史消息拼到 system prompt.
     */
    @Bean
    public MessageChatMemoryAdvisor memoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }
}