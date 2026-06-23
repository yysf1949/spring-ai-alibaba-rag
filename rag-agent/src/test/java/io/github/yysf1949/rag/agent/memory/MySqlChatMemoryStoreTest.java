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
 * Integration-style tests for {@link MySqlChatMemoryStore}. We use H2 in
 * MySQL-compat mode ({@code MODE=MySQL}) as the stand-in JDBC driver — H2's
 * MySQL mode emulates {@code TEXT} columns and most of the dialect quirks we
 * actually exercise (no stored procs, no ENGINE clauses — we stripped those
 * from the DDL to keep it portable).
 */
class MySqlChatMemoryStoreTest {

    private DataSource dataSource;
    private MySqlChatMemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:mysql-" + System.nanoTime() + ";DB_CLOSE_DELAY=-1;MODE=MySQL");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.store = new MySqlChatMemoryStore(dataSource);
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
                UserMessage.builder().text("hi").metadata(Map.of("src", "ios")).build(),
                new AssistantMessage("hello!"),
                new AssistantMessage(
                        "",
                        Map.of(),
                        List.of(new AssistantMessage.ToolCall(
                                "tc-1", "function", "kb_search", "{\"q\":\"refund\"}"))));

        store.saveAll("c", msgs);

        List<Message> back = store.findByConversationId("c");
        assertThat(back).hasSize(3);
        assertThat(back.get(0).getText()).isEqualTo("hi");
        assertThat(back.get(0).getMetadata()).containsEntry("src", "ios");
        assertThat(back.get(1).getText()).isEqualTo("hello!");
        AssistantMessage backTc = (AssistantMessage) back.get(2);
        assertThat(backTc.hasToolCalls()).isTrue();
        assertThat(backTc.getToolCalls().get(0).name()).isEqualTo("kb_search");
        assertThat(backTc.getToolCalls().get(0).arguments()).isEqualTo("{\"q\":\"refund\"}");
    }

    @Test
    void multipleConversationsAreIsolated() {
        store.saveAll("a", List.of(UserMessage.builder().text("aaa").build()));
        store.saveAll("b", List.of(UserMessage.builder().text("bbb").build()));

        assertThat(store.findByConversationId("a"))
                .singleElement().extracting(Message::getText).isEqualTo("aaa");
        assertThat(store.findByConversationId("b"))
                .singleElement().extracting(Message::getText).isEqualTo("bbb");
        assertThat(store.findConversationIds()).containsExactlyInAnyOrder("a", "b");
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
    void saveAllReplacesEntireConversation() {
        store.saveAll("c", List.of(
                UserMessage.builder().text("first").build(),
                UserMessage.builder().text("second").build()));

        store.saveAll("c", List.of(UserMessage.builder().text("only-this").build()));

        List<Message> back = store.findByConversationId("c");
        assertThat(back).hasSize(1);
        assertThat(back.get(0).getText()).isEqualTo("only-this");
    }
}