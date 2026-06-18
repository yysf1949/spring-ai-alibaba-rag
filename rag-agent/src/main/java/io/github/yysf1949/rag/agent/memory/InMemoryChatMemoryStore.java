package io.github.yysf1949.rag.agent.memory;

import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link ChatMemoryRepository} backed by a {@link ConcurrentHashMap}.
 *
 * <h2>When to use</h2>
 * <ul>
 *   <li><b>Default for dev / tests</b>: zero external dependencies, predictable.</li>
 *   <li>Single-JVM deployments where losing chat history on restart is acceptable.</li>
 *   <li>Integration tests that need a real {@link ChatMemoryRepository} bean
 *       without standing up a database.</li>
 * </ul>
 *
 * <h2>When NOT to use</h2>
 * <ul>
 *   <li>Multi-instance production — each pod has its own Map, users see "lost
 *       history" when their next request lands on a different replica.</li>
 *   <li>Anything that must survive a restart. Use {@code H2ChatMemoryStore}
 *       (file mode) or {@code MySqlChatMemoryStore} / {@code RedisChatMemoryStore}
 *       instead.</li>
 * </ul>
 *
 * <h2>Thread-safety</h2>
 * <p>The outer map is a {@link ConcurrentHashMap}. Inner lists are <em>not</em>
 * synchronised — {@link #saveAll} always replaces the whole list, which is
 * atomic from the caller's perspective but does not protect against concurrent
 * writers merging their views. Spring AI's {@code MessageWindowChatMemory}
 * serialises reads/writes through its own lock, so in the supported usage
 * pattern this is fine.</p>
 */
public class InMemoryChatMemoryStore implements ChatMemoryRepository {

    private final Map<String, List<Message>> store = new ConcurrentHashMap<>();

    private final MessageSerializer serializer = new MessageSerializer();

    @Override
    public List<String> findConversationIds() {
        return new ArrayList<>(store.keySet());
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<Message> messages = store.get(conversationId);
        if (messages == null) {
            return List.of();
        }
        // Return a defensive copy — Spring AI may mutate these (it doesn't,
        // but we don't promise that here).
        return new ArrayList<>(messages);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (messages == null || messages.isEmpty()) {
            // Empty list == delete; keeps findConversationIds() honest.
            store.remove(conversationId);
            return;
        }
        // Defensive copy on write so callers can't mutate stored state.
        List<Message> snapshot = new ArrayList<>(messages.size());
        for (Message m : messages) {
            snapshot.add(reconstruct(serializer.toRecord(m)));
        }
        store.put(conversationId, Collections.unmodifiableList(snapshot));
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null) {
            return;
        }
        store.remove(conversationId);
    }

    /**
     * Round-trip through the serialiser to make sure we are storing plain
     * Spring AI instances — if a caller passed us a custom Message subtype,
     * we drop the custom fields but at least the {@code textContent} /
     * {@code messageType} survive. This is the same constraint as JDBC and
     * Redis stores so the in-memory mode is a faithful preview of persisted mode.
     */
    private Message reconstruct(MessageRecord rec) {
        return serializer.fromRecord(rec);
    }
}