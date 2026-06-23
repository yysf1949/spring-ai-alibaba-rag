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

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * H2-flavoured {@link ChatMemoryRepository} backed by a single {@code chat_memory}
 * table. Schema:
 *
 * <pre>{@code
 * CREATE TABLE chat_memory (
 *   conversation_id  VARCHAR(128) NOT NULL,
 *   seq              INT          NOT NULL,   -- ordinal within the conversation
 *   message_type     VARCHAR(16)  NOT NULL,   -- USER / ASSISTANT / SYSTEM / TOOL
 *   content          CLOB         NULL,
 *   metadata_json    CLOB         NULL,
 *   tool_calls_json  CLOB         NULL,
 *   tool_resp_json   CLOB         NULL,
 *   updated_at       TIMESTAMP    NOT NULL,
 *   PRIMARY KEY (conversation_id, seq)
 * );
 * }</pre>
 *
 * <h2>Lifecycle</h2>
 * <ul>
 *   <li>{@link #ensureSchema()} is idempotent — creates the table on first call.</li>
 *   <li>External code (typically {@code rag-app}'s
 *       {@code spring.sql.init.schema-locations} or a Flyway migration) is
 *       responsible for invoking it before the first {@code findByConversationId}.</li>
 * </ul>
 *
 * <h2>Why H2 by default</h2>
 * <p>Single-file embedded database — gives us a "real" persistent store with
 * zero infrastructure. Acceptable for the demo deployment; production multi-pod
 * should swap to {@link MySqlChatMemoryStore} or {@code RedisChatMemoryStore}.</p>
 */
public class H2ChatMemoryStore implements ChatMemoryRepository {

    private static final Logger log = LoggerFactory.getLogger(H2ChatMemoryStore.class);

    private static final String TABLE = "chat_memory";
    private static final String SCHEMA_DDL = """
            CREATE TABLE IF NOT EXISTS chat_memory (
              conversation_id  VARCHAR(128) NOT NULL,
              seq              INT          NOT NULL,
              message_type     VARCHAR(16)  NOT NULL,
              content          CLOB         NULL,
              metadata_json    CLOB         NULL,
              tool_calls_json  CLOB         NULL,
              tool_resp_json   CLOB         NULL,
              updated_at       TIMESTAMP    NOT NULL,
              PRIMARY KEY (conversation_id, seq)
            )
            """;

    private final DataSource dataSource;
    private final MessageSerializer serializer = new MessageSerializer();
    private final ObjectMapper mapper = buildMapper();

    private final TypeReference<List<MessageRecord.ToolCallRecord>> toolCallListType =
            new TypeReference<>() {};
    private final TypeReference<List<MessageRecord.ToolResponseRecord>> toolResponseListType =
            new TypeReference<>() {};
    private final TypeReference<java.util.Map<String, Object>> metadataType =
            new TypeReference<>() {};

    public H2ChatMemoryStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper m = new ObjectMapper();
        m.registerModule(new JavaTimeModule());
        m.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        m.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        return m;
    }

    /** Idempotent — safe to call multiple times. */
    public void ensureSchema() {
        try (Connection c = dataSource.getConnection();
             Statement s = c.createStatement()) {
            s.execute(SCHEMA_DDL);
            log.info("H2ChatMemoryStore schema ready (table={})", TABLE);
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to create chat_memory table", ex);
        }
    }

    @Override
    public List<String> findConversationIds() {
        List<String> out = new ArrayList<>();
        String sql = "SELECT DISTINCT conversation_id FROM " + TABLE + " ORDER BY conversation_id";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(rs.getString(1));
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("findConversationIds failed", ex);
        }
        return out;
    }

    @Override
    public List<Message> findByConversationId(String conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<MessageRecord> records = new ArrayList<>();
        String sql = "SELECT message_type, content, metadata_json, tool_calls_json, tool_resp_json "
                   + "FROM " + TABLE + " WHERE conversation_id = ? ORDER BY seq ASC";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, conversationId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(readRecord(rs));
                }
            }
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "findByConversationId failed for id=" + conversationId, ex);
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
        // Replace-all strategy — easier to reason about, mirrors Spring AI's InMemory default.
        Timestamp now = Timestamp.from(Instant.now());
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement del = c.prepareStatement(
                    "DELETE FROM " + TABLE + " WHERE conversation_id = ?")) {
                del.setString(1, conversationId);
                del.executeUpdate();
            }
            try (PreparedStatement ins = c.prepareStatement(
                    "INSERT INTO " + TABLE
                  + " (conversation_id, seq, message_type, content, metadata_json, "
                  + "  tool_calls_json, tool_resp_json, updated_at) "
                  + "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
                int seq = 0;
                for (Message m : messages) {
                    MessageRecord rec = serializer.toRecord(m);
                    ins.setString(1, conversationId);
                    ins.setInt(2, seq++);
                    ins.setString(3, rec.type());
                    ins.setString(4, rec.content());
                    ins.setString(5, toJson(rec.metadata()));
                    ins.setString(6, toJson(rec.toolCalls()));
                    ins.setString(7, toJson(rec.toolResponses()));
                    ins.setTimestamp(8, now);
                    ins.addBatch();
                }
                ins.executeBatch();
            }
            c.commit();
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "saveAll failed for id=" + conversationId, ex);
        }
    }

    @Override
    public void deleteByConversationId(String conversationId) {
        if (conversationId == null) {
            return;
        }
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM " + TABLE + " WHERE conversation_id = ?")) {
            ps.setString(1, conversationId);
            ps.executeUpdate();
        } catch (SQLException ex) {
            throw new IllegalStateException(
                    "deleteByConversationId failed for id=" + conversationId, ex);
        }
    }

    // --- internal helpers ---------------------------------------------------

    private MessageRecord readRecord(ResultSet rs) throws SQLException {
        String type = rs.getString("message_type");
        String content = rs.getString("content");
        java.util.Map<String, Object> metadata = fromJson(rs.getString("metadata_json"), metadataType);
        List<MessageRecord.ToolCallRecord> calls =
                fromJson(rs.getString("tool_calls_json"), toolCallListType);
        List<MessageRecord.ToolResponseRecord> responses =
                fromJson(rs.getString("tool_resp_json"), toolResponseListType);
        return new MessageRecord(type, content, metadata, calls, responses);
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Failed to serialise to JSON", ex);
        }
    }

    private <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException(
                    "Failed to deserialise JSON: " + json, ex);
        }
    }
}