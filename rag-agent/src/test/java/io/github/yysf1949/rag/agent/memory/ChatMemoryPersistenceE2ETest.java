package io.github.yysf1949.rag.agent.memory;

import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 18 P1 T1.4 — Real DeepSeek end-to-end test that the
 * {@link H2ChatMemoryStore} actually persists multi-turn conversations and
 * survives a "JVM restart" (i.e. reopening the same on-disk DB file).
 *
 * <h2>Why this is the proof that P1 ships</h2>
 * <ul>
 *   <li>Phase 16's {@code InMemoryChatMemoryRepository} is fine for demos but
 *       resets on every restart — the user's complaint logged in the lessons
 *       summary.</li>
 *   <li>The unit tests for {@code H2ChatMemoryStore} prove round-trip works
 *       <em>in isolation</em>. This test proves the same store works
 *       <em>through Spring AI's {@link MessageChatMemoryAdvisor}</em> with a
 *       real LLM writing real messages, then re-opening the file later
 *       replays the conversation.</li>
 * </ul>
 *
 * <h2>Activation</h2>
 * <p>{@code @EnabledIfEnvironmentVariable("DEEPSEEK_API_KEY")} — skipped in
 * CI without a key. Locally: {@code DEEPSEEK_API_KEY=*** mvn -pl rag-agent
 * test -Dtest=ChatMemoryPersistenceE2ETest}.</p>
 *
 * <h2>What this test does not do</h2>
 * <p>We don't spin up {@code ChatClientService} — that brings in Spring
 * context, tool descriptors, ctx filtering and a lot of Phase 14 wiring
 * unrelated to memory persistence. The goal here is laser-focused: prove
 * <em>memory persistence</em>, nothing else.</p>
 */
@DisplayName("ChatMemory Real DeepSeek Multi-Turn Persistence E2E")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class ChatMemoryPersistenceE2ETest {

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";
    private static final String CONVERSATION_ID_KEY = "chat_memory_conversation_id";

    private Path dbFile;
    private String dbUrl;

    @BeforeEach
    void setUp() throws IOException {
        // Use a tmpfile-backed H2 so we can prove cross-restart persistence.
        // DB_CLOSE_ON_EXIT=FALSE keeps the file open after the connection closes.
        dbFile = Files.createTempFile("rag-chat-memory-e2e-", ".mv.db");
        Files.deleteIfExists(dbFile); // H2 creates a .mv.db; the placeholder is fine to remove
        dbUrl = "jdbc:h2:" + dbFile.toString().replace(".mv.db", "")
              + ";DB_CLOSE_ON_EXIT=FALSE;MODE=MySQL";
    }

    @AfterEach
    void tearDown() throws IOException {
        // Best-effort cleanup of H2 files
        try {
            Files.deleteIfExists(dbFile);
            Path wal = Path.of(dbFile.toString().replace(".mv.db", ".trace.db"));
            Files.deleteIfExists(wal);
        } catch (IOException ignored) {
            // H2 may have already cleaned up
        }
    }

    @Test
    @DisplayName("Multi-turn 真 DeepSeek + H2 store 跨 JVM 重启持久化")
    void multiTurn_deepSeek_persistsAcrossJvmRestart() throws Exception {
        // === ROUND 1: open store, do 2 turns with real DeepSeek ===
        DataSource ds1 = newDataSource(dbUrl);
        H2ChatMemoryStore store1 = new H2ChatMemoryStore(ds1);
        store1.ensureSchema();
        ChatMemory memory1 = MessageWindowChatMemory.builder()
                .chatMemoryRepository(store1)
                .maxMessages(20)
                .build();
        MessageChatMemoryAdvisor advisor = MessageChatMemoryAdvisor.builder(memory1).build();
        ChatClient client = newDeepSeekClient();

        String convId = "e2e-conv-" + System.nanoTime();

        // Turn 1: ask the model to remember a number.
        String reply1 = client.prompt()
                .system("You are a concise assistant. Always follow the user's instruction exactly. Be brief.")
                .user("Remember this number: 42. Reply with just 'OK, stored'.")
                .advisors(advisor)
                .advisors(a -> a.param(CONVERSATION_ID_KEY, convId))
                .call()
                .content();
        assertThat(reply1).as("Turn 1 must produce a non-empty reply").isNotBlank();
        System.out.println("[P1 E2E] Turn 1 reply: " + reply1);

        // Turn 2: ask the model to recall the number. The advisor must have
        // injected the prior turn via H2ChatMemoryStore.
        String reply2 = client.prompt()
                .system("You are a concise assistant.")
                .user("What number did I just ask you to remember? Reply with just the number.")
                .advisors(advisor)
                .advisors(a -> a.param(CONVERSATION_ID_KEY, convId))
                .call()
                .content();
        assertThat(reply2).as("Turn 2 must produce a non-empty reply").isNotBlank();
        System.out.println("[P1 E2E] Turn 2 reply: " + reply2);
        assertThat(reply2)
                .as("LLM should remember '42' from Turn 1 (proves memory survives through H2ChatMemoryStore)")
                .contains("42");

        // Snapshot H2 contents — at least 4 messages expected:
        // user1, assistant1, user2, assistant2
        List<Message> snapshot1 = store1.findByConversationId(convId);
        assertThat(snapshot1)
                .as("After 2 turns the H2 store must contain >= 4 messages")
                .hasSizeGreaterThanOrEqualTo(4);
        long userCount = snapshot1.stream()
                .filter(m -> "USER".equals(m.getMessageType().name())).count();
        long assistantCount = snapshot1.stream()
                .filter(m -> "ASSISTANT".equals(m.getMessageType().name())).count();
        assertThat(userCount).as("two user messages expected").isGreaterThanOrEqualTo(2);
        assertThat(assistantCount).as("two assistant messages expected").isGreaterThanOrEqualTo(2);

        // === ROUND 2: simulate "JVM restart" by opening a brand new store on the same file ===
        // Drop the in-memory references — anything still pointing at this JVM's
        // H2 page cache is fair game to keep, but we're proving the on-disk
        // state alone is enough.
        store1 = null;
        memory1 = null;
        advisor = null;

        DataSource ds2 = newDataSource(dbUrl);
        H2ChatMemoryStore store2 = new H2ChatMemoryStore(ds2);
        // Do NOT call ensureSchema() again — prove the table already exists from Round 1.

        List<Message> snapshot2 = store2.findByConversationId(convId);
        assertThat(snapshot2)
                .as("After JVM restart, opening the same H2 file must surface the prior conversation")
                .hasSizeGreaterThanOrEqualTo(4);

        // And the content survived too — the assistant's "OK, stored" reply
        // must still be there.
        boolean sawStoredReply = snapshot2.stream()
                .filter(m -> "ASSISTANT".equals(m.getMessageType().name()))
                .map(Message::getText)
                .anyMatch(t -> t != null && t.toUpperCase().contains("OK"));
        assertThat(sawStoredReply)
                .as("Round-1 assistant reply must survive a JVM restart")
                .isTrue();
    }

    private static DataSource newDataSource(String url) {
        JdbcDataSource ds = new JdbcDataSource();
        ds.setURL(url);
        ds.setUser("sa");
        ds.setPassword("");
        return ds;
    }

    private static ChatClient newDeepSeekClient() {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(apiKey)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(DEEPSEEK_MODEL)
                .temperature(0.2)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
        return ChatClient.create(model);
    }
}