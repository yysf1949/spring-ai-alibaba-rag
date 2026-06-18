package io.github.yysf1949.rag.agent.memory;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration-style tests for {@link JdbcChatMemoryStore}. We use H2 in
 * PostgreSQL-compat mode as the stand-in JDBC driver — H2 is an ANSI-SQL
 * superset so the same DDL and statements work.
 */
class JdbcChatMemoryStoreTest {

    private DataSource dataSource;
    private JdbcChatMemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:jdbc-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.store = new JdbcChatMemoryStore(dataSource);
        this.store.ensureSchema();
    }

    @Test
    void ensureSchemaIsIdempotent() {
        store.ensureSchema();
        store.ensureSchema();
    }

    @Test
    void saveAllThenFindRoundTrip() {
        List<Message> msgs = List.of(
                UserMessage.builder().text("hi").metadata(Map.of("k", "v")).build(),
                new AssistantMessage("hello!"));

        store.saveAll("c", msgs);

        List<Message> back = store.findByConversationId("c");
        assertThat(back).hasSize(2);
        assertThat(back.get(0).getText()).isEqualTo("hi");
        assertThat(back.get(0).getMetadata()).containsEntry("k", "v");
        assertThat(back.get(1).getText()).isEqualTo("hello!");
    }

    @Test
    void multipleConversationsAreIsolated() {
        store.saveAll("a", List.of(UserMessage.builder().text("aaa").build()));
        store.saveAll("b", List.of(UserMessage.builder().text("bbb").build()));

        assertThat(store.findByConversationId("a"))
                .singleElement().extracting(Message::getText).isEqualTo("aaa");
        assertThat(store.findByConversationId("b"))
                .singleElement().extracting(Message::getText).isEqualTo("bbb");
    }

    @Test
    void deleteByConversationIdRemovesRows() {
        store.saveAll("c-1", List.of(UserMessage.builder().text("a").build()));
        store.saveAll("c-2", List.of(UserMessage.builder().text("b").build()));

        store.deleteByConversationId("c-1");

        assertThat(store.findByConversationId("c-1")).isEmpty();
        assertThat(store.findByConversationId("c-2")).hasSize(1);
    }

    @Test
    void assistantMessageWithToolCallsRoundTrip() {
        AssistantMessage.ToolCall tc = new AssistantMessage.ToolCall(
                "tc-1", "function", "kb_search", "{\"q\":\"x\"}");
        AssistantMessage am = new AssistantMessage("", Map.of(), List.of(tc));

        store.saveAll("c-tc", List.of(am));

        Message back = store.findByConversationId("c-tc").get(0);
        assertThat(back).isInstanceOf(AssistantMessage.class);
        AssistantMessage backAsm = (AssistantMessage) back;
        assertThat(backAsm.hasToolCalls()).isTrue();
        assertThat(backAsm.getToolCalls().get(0).name()).isEqualTo("kb_search");
    }

    @Test
    void emptySaveAllClearsConversation() {
        store.saveAll("c", List.of(UserMessage.builder().text("hi").build()));
        store.saveAll("c", List.of());

        assertThat(store.findByConversationId("c")).isEmpty();
    }
}