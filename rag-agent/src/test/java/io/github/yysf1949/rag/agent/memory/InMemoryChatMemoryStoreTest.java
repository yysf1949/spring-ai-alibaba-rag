package io.github.yysf1949.rag.agent.memory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for {@link InMemoryChatMemoryStore}.
 *
 * <p>This store is the Phase 16 fallback (the {@code @ConditionalOnMissingBean}
 * branch in {@code ChatMemoryConfig}). Anything broken here will silently
 * regress multi-turn chat across every deployment that doesn't override the
 * store property.</p>
 */
class InMemoryChatMemoryStoreTest {

    private ChatMemoryRepository repo;

    @BeforeEach
    void setUp() {
        repo = new InMemoryChatMemoryStore();
    }

    @Test
    void saveAllThenFindRoundTrip() {
        List<Message> msgs = List.of(
                UserMessage.builder().text("hi").build(),
                new AssistantMessage("hello!"));

        repo.saveAll("c-1", msgs);

        List<Message> back = repo.findByConversationId("c-1");
        assertThat(back).hasSize(2);
        assertThat(back.get(0).getText()).isEqualTo("hi");
        assertThat(back.get(0).getMessageType().name()).isEqualTo("USER");
        assertThat(back.get(1).getText()).isEqualTo("hello!");
        assertThat(back.get(1).getMessageType().name()).isEqualTo("ASSISTANT");
    }

    @Test
    void multipleConversationsAreIsolated() {
        repo.saveAll("c-1", List.of(UserMessage.builder().text("first").build()));
        repo.saveAll("c-2", List.of(UserMessage.builder().text("second").build()));

        assertThat(repo.findByConversationId("c-1"))
                .singleElement().extracting(Message::getText).isEqualTo("first");
        assertThat(repo.findByConversationId("c-2"))
                .singleElement().extracting(Message::getText).isEqualTo("second");
    }

    @Test
    void emptyListDeleteClearsConversation() {
        repo.saveAll("c-1", List.of(UserMessage.builder().text("anything").build()));
        assertThat(repo.findByConversationId("c-1")).hasSize(1);

        repo.saveAll("c-1", List.of());

        assertThat(repo.findByConversationId("c-1")).isEmpty();
        assertThat(repo.findConversationIds()).doesNotContain("c-1");
    }

    @Test
    void deleteByConversationIdRemovesEntry() {
        repo.saveAll("c-1", List.of(UserMessage.builder().text("first").build()));
        repo.saveAll("c-2", List.of(UserMessage.builder().text("second").build()));

        repo.deleteByConversationId("c-1");

        assertThat(repo.findByConversationId("c-1")).isEmpty();
        assertThat(repo.findByConversationId("c-2")).hasSize(1);
    }

    @Test
    void nullConversationIdYieldsEmptyList() {
        assertThat(repo.findByConversationId(null)).isEmpty();
    }

    @Test
    void nullConversationIdOnSaveAllRejected() {
        assertThatThrownBy(() -> repo.saveAll(null, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void findConversationIdsReturnsAllStoredIds() {
        repo.saveAll("c-1", List.of(UserMessage.builder().text("a").build()));
        repo.saveAll("c-2", List.of(UserMessage.builder().text("b").build()));
        repo.saveAll("c-3", List.of(UserMessage.builder().text("c").build()));

        assertThat(repo.findConversationIds())
                .containsExactlyInAnyOrder("c-1", "c-2", "c-3");
    }

    @Test
    void findByConversationIdReturnsDefensiveCopy() {
        Message u = UserMessage.builder().text("hi").build();
        repo.saveAll("c-1", List.of(u));

        List<Message> first = repo.findByConversationId("c-1");
        first.clear(); // mutating the returned list should NOT affect storage

        List<Message> second = repo.findByConversationId("c-1");
        assertThat(second).hasSize(1); // storage intact
        assertThat(second.get(0).getText()).isEqualTo("hi");
    }

    @Test
    void saveAllReplacesEntireConversation() {
        repo.saveAll("c-1", List.of(
                UserMessage.builder().text("first").build(),
                UserMessage.builder().text("second").build()));
        // Now write only the second turn — first turn must be dropped.
        repo.saveAll("c-1", List.of(UserMessage.builder().text("only-this").build()));

        List<Message> back = repo.findByConversationId("c-1");
        assertThat(back).hasSize(1);
        assertThat(back.get(0).getText()).isEqualTo("only-this");
    }

    @Test
    void assistantMessageWithMetadataRoundTrip() {
        AssistantMessage am = new AssistantMessage(
                "Here's the answer.",
                Map.of("traceId", "t-1", "tokens", 17));
        repo.saveAll("c-1", List.of(am));

        Message back = repo.findByConversationId("c-1").get(0);
        assertThat(back).isInstanceOf(AssistantMessage.class);
        assertThat(back.getText()).isEqualTo("Here's the answer.");
        assertThat(back.getMetadata()).containsEntry("traceId", "t-1");
    }
}