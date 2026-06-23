package io.github.yysf1949.rag.agent.memory;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
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
 * Integration-style tests for {@link H2ChatMemoryStore} using H2 in-memory mode.
 *
 * <p>Each test gets its own private H2 database ({@code jdbc:h2:mem:test-...};
 * {@code DB_CLOSE_DELAY=-1} keeps it alive for the test method). Tests verify
 * (a) {@code ensureSchema} is idempotent, (b) round-trip preserves every
 * concrete {@link Message} subclass, (c) conversation isolation, (d) delete.</p>
 */
class H2ChatMemoryStoreTest {

    private DataSource dataSource;
    private H2ChatMemoryStore store;

    @BeforeEach
    void setUp() throws Exception {
        // Fresh in-memory H2 per test — DB_CLOSE_DELAY=-1 keeps it alive across
        // multiple connections inside the same test method.
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL("jdbc:h2:mem:" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        ds.setUser("sa");
        ds.setPassword("");
        this.dataSource = ds;
        this.store = new H2ChatMemoryStore(dataSource);
        this.store.ensureSchema();
    }

    @AfterEach
    void tearDown() throws Exception {
        // Best-effort cleanup. The JVM terminates the in-memory DB anyway.
        try (var c = dataSource.getConnection();
             var s = c.createStatement()) {
            s.execute("DROP ALL OBJECTS DELETE FILES");
        }
    }

    @Test
    void ensureSchemaIsIdempotent() {
        // Calling twice must not throw — second call simply hits the IF NOT EXISTS clause.
        store.ensureSchema();
        store.ensureSchema();
    }

    @Test
    void saveAllThenFindRoundTrip() {
        List<Message> msgs = List.of(
                UserMessage.builder()
                        .text("What's your return policy?")
                        .metadata(Map.of("source", "chat"))
                        .build(),
                new AssistantMessage("Within 7 days, full refund."),
                new AssistantMessage(
                        "",
                        Map.of(),
                        List.of(new AssistantMessage.ToolCall(
                                "tc-1", "function", "get_order",
                                "{\"orderId\":\"O-100\"}"))));

        store.saveAll("conv-1", msgs);

        List<Message> back = store.findByConversationId("conv-1");
        assertThat(back).hasSize(3);
        assertThat(back.get(0).getText()).isEqualTo("What's your return policy?");
        assertThat(back.get(0).getMetadata()).containsEntry("source", "chat");
        assertThat(back.get(1).getText()).isEqualTo("Within 7 days, full refund.");
        // The third message had no content, only a tool call.
        assertThat(back.get(2).getMessageType().name()).isEqualTo("ASSISTANT");
        assertThat(((AssistantMessage) back.get(2)).hasToolCalls()).isTrue();
        assertThat(((AssistantMessage) back.get(2)).getToolCalls().get(0).name())
                .isEqualTo("get_order");
    }

    @Test
    void multipleConversationsAreIsolated() {
        store.saveAll("a", List.of(UserMessage.builder().text("a-msg").build()));
        store.saveAll("b", List.of(UserMessage.builder().text("b-msg").build()));

        assertThat(store.findByConversationId("a"))
                .singleElement().extracting(Message::getText).isEqualTo("a-msg");
        assertThat(store.findByConversationId("b"))
                .singleElement().extracting(Message::getText).isEqualTo("b-msg");
        assertThat(store.findConversationIds()).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void emptyListClearsConversation() {
        store.saveAll("c", List.of(UserMessage.builder().text("hi").build()));
        assertThat(store.findByConversationId("c")).hasSize(1);

        store.saveAll("c", List.of());

        assertThat(store.findByConversationId("c")).isEmpty();
        assertThat(store.findConversationIds()).doesNotContain("c");
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

    @Test
    void findByConversationIdReturnsEmptyForUnknownId() {
        assertThat(store.findByConversationId("never-saved")).isEmpty();
    }

    @Test
    void metadataRoundTripPreservesKeysAndValues() {
        UserMessage u = UserMessage.builder()
                .text("hi")
                .metadata(Map.of("k1", "v1", "k2", 42))
                .build();
        store.saveAll("c", List.of(u));

        Message back = store.findByConversationId("c").get(0);
        assertThat(back.getMetadata()).containsEntry("k1", "v1");
        // Jackson may serialise integer 42 as Integer or Long depending on the
        // path; accept either as long as the toString is "42".
        assertThat(back.getMetadata().get("k2").toString()).isEqualTo("42");
    }
}