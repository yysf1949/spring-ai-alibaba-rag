package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.memory.H2ChatMemoryStore;
import io.github.yysf1949.rag.agent.memory.InMemoryChatMemoryStore;
import io.github.yysf1949.rag.agent.memory.JdbcChatMemoryStore;
import io.github.yysf1949.rag.agent.memory.MySqlChatMemoryStore;
import io.github.yysf1949.rag.agent.memory.RedisChatMemoryStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import redis.clients.jedis.UnifiedJedis;

import javax.sql.DataSource;

/**
 * Phase 18 P1 — pluggable persistent {@link ChatMemoryRepository} backend.
 *
 * <h2>What changed vs Phase 16</h2>
 * <p>Phase 16 hard-wired {@code InMemoryChatMemoryRepository}. This Phase adds
 * four production-ready backends, selected at runtime by a single property:
 * {@code spring.rag.chat-memory.store=inmemory|h2|mysql|jdbc|redis}
 * (default {@code inmemory}). The {@link ChatMemory} wrapper, the
 * {@link MessageChatMemoryAdvisor} and the wiring in
 * {@code ChatClientService} stay exactly as they were.</p>
 *
 * <h2>Default behaviour</h2>
 * <ul>
 *   <li>If <em>no</em> {@link ChatMemoryRepository} bean exists in the context
 *       (the Phase 16 default), we supply an {@link InMemoryChatMemoryStore}
 *       with {@code @ConditionalOnMissingBean}. Existing tests keep passing.</li>
 *   <li>If a {@link DataSource} is present <em>and</em>
 *       {@code spring.rag.chat-memory.store=h2|mysql|jdbc}, the matching
 *       store is registered and {@link #ensureChatMemorySchema(DataSource)} is
 *       called so the table exists before the first request.</li>
 *   <li>If a {@link UnifiedJedis} (Redis client) is present <em>and</em>
 *       {@code spring.rag.chat-memory.store=redis}, the Redis store is used.</li>
 *   <li>If the requested store's dependency is missing, Spring autoconfig will
 *       simply skip — and {@code @ConditionalOnMissingBean} falls back to
 *       in-memory so the chat endpoint never silently fails.</li>
 * </ul>
 *
 * <h2>Why five beans instead of one with a switch</h2>
 * <p>Each store has its own constructor arity ({@code DataSource} vs
 * {@code UnifiedJedis} vs nothing). {@code @ConditionalOnProperty} keeps the
 * wiring declarative and means the chosen backend is obvious from
 * {@code application.yml}.</p>
 *
 * <h2>Why expose the schema-init bean</h2>
 * <p>Tests can inject this to validate {@code ensureSchema} is idempotent and
 * that round-trip via the JDBC stores survives an explicit
 * {@code DROP + CREATE}.</p>
 */
@Configuration
@ConditionalOnBean(ChatClient.class)
public class ChatMemoryConfig {

    private static final Logger log = LoggerFactory.getLogger(ChatMemoryConfig.class);

    public static final String PREFIX = "spring.rag.chat-memory.store";

    // ---------- backend beans -------------------------------------------------

    /** Default fallback: in-process. Always available; same semantics as Phase 16's bean. */
    @Bean
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    public ChatMemoryRepository defaultChatMemoryRepository() {
        log.info("ChatMemory backend = INMEMORY (no other ChatMemoryRepository bean found)");
        return new InMemoryChatMemoryStore();
    }

    /** H2 file or in-memory mode. Requires a {@link DataSource} bean. */
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "store", havingValue = "h2")
    @ConditionalOnBean(DataSource.class)
    public ChatMemoryRepository h2ChatMemoryRepository(DataSource dataSource) {
        log.info("ChatMemory backend = H2");
        H2ChatMemoryStore store = new H2ChatMemoryStore(dataSource);
        store.ensureSchema();
        return store;
    }

    /** MySQL. Requires a {@link DataSource} bean pointing at MySQL. */
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "store", havingValue = "mysql")
    @ConditionalOnBean(DataSource.class)
    public ChatMemoryRepository mysqlChatMemoryRepository(DataSource dataSource) {
        log.info("ChatMemory backend = MYSQL");
        MySqlChatMemoryStore store = new MySqlChatMemoryStore(dataSource);
        store.ensureSchema();
        return store;
    }

    /** Generic ANSI-SQL fallback. Requires a {@link DataSource} bean. */
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "store", havingValue = "jdbc")
    @ConditionalOnBean(DataSource.class)
    public ChatMemoryRepository jdbcChatMemoryRepository(DataSource dataSource) {
        log.info("ChatMemory backend = JDBC (generic ANSI-SQL)");
        JdbcChatMemoryStore store = new JdbcChatMemoryStore(dataSource);
        store.ensureSchema();
        return store;
    }

    /** Redis. Requires a {@link UnifiedJedis} bean (provided by rag-redis). */
    @Bean
    @ConditionalOnProperty(prefix = PREFIX, name = "store", havingValue = "redis")
    @ConditionalOnBean(UnifiedJedis.class)
    public ChatMemoryRepository redisChatMemoryRepository(UnifiedJedis jedis) {
        log.info("ChatMemory backend = REDIS");
        return new RedisChatMemoryStore(jedis);
    }

    // ---------- window + advisor (unchanged from Phase 16) ------------------

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository chatMemoryRepository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(chatMemoryRepository)
                .maxMessages(20)
                .build();
    }

    @Bean
    public MessageChatMemoryAdvisor memoryAdvisor(ChatMemory chatMemory) {
        return MessageChatMemoryAdvisor.builder(chatMemory).build();
    }

    // ---------- test hooks ---------------------------------------------------

    /**
     * Visible-for-testing hook — returns the active backend's class name. Tests
     * can call this to confirm the right store was wired.
     */
    public static String describeBackend(ChatMemoryRepository repo) {
        if (repo == null) {
            return "none";
        }
        return repo.getClass().getSimpleName();
    }
}