package io.github.yysf1949.rag.agent.memory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.messages.Message;
import redis.clients.jedis.JedisPooled;
import redis.clients.jedis.UnifiedJedis;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Redis-backed {@link ChatMemoryRepository}.
 *
 * <h2>Key layout</h2>
 * <ul>
 *   <li>{@code rag:chat-memory:conv:{conversationId}} → JSON blob, the full
 *       conversation as a {@code List<MessageRecord>}.</li>
 *   <li>{@code rag:chat-memory:index} → Redis SET of known conversation IDs.
 *       {@link #findConversationIds} returns this set; {@link #saveAll} keeps it
 *       in sync.</li>
 * </ul>
 *
 * <h2>Why one blob per conversation instead of one key per message</h2>
 * <p>The total conversation is small (≤ 20 messages after MessageWindowChatMemory).
 * Pipelining one record at a time would balloon the round-trip count and make
 * transactions over a conversation harder. The blob approach also keeps the
 * schema identical to the JDBC stores — a single source of truth.</p>
 *
 * <h2>Why no TTL by default</h2>
 * <p>Chat history is small but irreplaceable for as long as a session is active.
 * Phase 19 will add a per-tenant TTL policy via
 * {@code spring.rag.chat-memory.ttl-seconds}. For now: live forever.</p>
 */
public class RedisChatMemoryStore implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(RedisChatMemoryStore.class);

    private static final String KEY_PREFIX = "rag:chat-memory:conv:";
    private static final String INDEX_KEY = "rag:chat-memory:index";

    private final UnifiedJedis jedis;
    private final MessageSerializer serializer = new MessageSerializer();
    private final ObjectMapper mapper = buildMapper();

    private final TypeReference<List<MessageRecord>> recordListType = new TypeReference<>() {};

    /**
     * @param jedis any {@link UnifiedJedis} (typically a {@link JedisPooled}).
     *              Pulled from {@code RedisConnection.client()} in
     *              {@code rag-redis} via DI.
     */
    public RedisChatMemoryStore(UnifiedJedis jedis) {
        this.jedis = jedis;
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return m;
    }

    @Override
    public List<String> findConversationIds() {
        Set<String> ids = jedis.smembers(INDEX_KEY);
        if (ids == null || ids.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(new HashSet<>(ids));
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        String json = jedis.get(KEY_PREFIX + conversationId);
        if (json == null) {
            return List.of();
        }
        List<MessageRecord> records;
        try {
            records = mapper.readValue(json, recordListType);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to deserialise conversation " + conversationId, ex);
        }
        return serializer.fromRecords(records);
    }

    @Override
    public void saveAll(String conversationId, List<Message> messages) {
        if (conversationId == null) {
            throw new IllegalArgumentException("conversationId must not be null");
        }
        if (messages == null || messages.isEmpty()) {
            deleteByConversationId(conversationId);
            return;
        }
        List<MessageRecord> records = serializer.toRecords(messages);
        String json;
        try {
            json = mapper.writeValueAsString(records);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise conversation", ex);
        }
        try {
            jedis.set(KEY_PREFIX + conversationId, json);
            jedis.sadd(INDEX_KEY, conversationId);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Redis saveAll failed for id=" + conversationId, ex);
        }
        log.debug("RedisChatMemoryStore.saveAll id={} messages={}", conversationId, messages.size());
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try {
            jedis.del(KEY_PREFIX + conversationId);
            jedis.srem(INDEX_KEY, conversationId);
        } catch (RuntimeException ex) {
            throw new IllegalStateException(
                    "Redis delete failed for id=" + conversationId, ex);
        }
    }
}