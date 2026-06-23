package io.github.yysf1949.rag.agent.feedback.store;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.Thumb;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * H2 持久化反馈仓库 — {@code @Profile("h2")} 激活。
 *
 * <p>DDL 见 {@code rag-agent/src/main/resources/schema-h2.sql}。使用 H2 MERGE 做 upsert。
 * 时间戳用 {@code BIGINT} epoch millis，避免时区问题。</p>
 */
@Component
@Profile("h2")
public class H2FeedbackRepository implements FeedbackPort {

    private final JdbcTemplate jdbc;

    private static final RowMapper<FeedbackRecord> MAPPER = (rs, row) -> new FeedbackRecord(
            rs.getString("feedback_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("conversation_id"),
            rs.getString("message_id"),
            parseThumb(rs.getString("thumb")),
            readRating(rs),
            rs.getString("comment"),
            rs.getString("source_channel"),
            rs.getString("kb_version"),
            rs.getLong("created_at")
    );

    public H2FeedbackRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public FeedbackRecord save(FeedbackRecord record) {
        jdbc.update(
                "MERGE INTO agent_feedback (feedback_id, tenant_id, user_id, conversation_id, "
                        + "message_id, thumb, rating, comment, source_channel, kb_version, created_at) "
                        + "KEY(feedback_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.feedbackId(),
                record.tenantId(),
                record.userId(),
                record.conversationId(),
                record.messageId(),
                record.thumb() == null ? null : record.thumb().name(),
                record.rating(),
                record.comment(),
                record.sourceChannel(),
                record.kbVersion(),
                record.createdAt()
        );
        return record;
    }

    @Override
    public Optional<FeedbackRecord> findById(String tenantId, String feedbackId) {
        return jdbc.query(
                "SELECT * FROM agent_feedback WHERE feedback_id = ? AND tenant_id = ?",
                MAPPER, feedbackId, tenantId
        ).stream().findFirst();
    }

    @Override
    public List<FeedbackRecord> findByConversation(String tenantId, String conversationId) {
        return jdbc.query(
                "SELECT * FROM agent_feedback WHERE tenant_id = ? AND conversation_id = ? "
                        + "ORDER BY created_at ASC",
                MAPPER, tenantId, conversationId
        );
    }

    @Override
    public List<FeedbackRecord> findByTenant(String tenantId, int limit) {
        return jdbc.query(
                "SELECT * FROM agent_feedback WHERE tenant_id = ? "
                        + "ORDER BY created_at ASC LIMIT ?",
                MAPPER, tenantId, limit
        );
    }

    @Override
    public long countByTenant(String tenantId) {
        Long n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_feedback WHERE tenant_id = ?",
                Long.class, tenantId
        );
        return n == null ? 0L : n;
    }

    @Override
    public List<FeedbackRecord> findByTenantRange(String tenantId, Long fromMs, Long toMs, int limit) {
        // Phase 40 T2: JSONL 导出专用 — 时间范围 + tenant 隔离 + 时间升序.
        // SQL 用 ? 占位避免注入; from/to 任一为 null 时跳过该条件.
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM agent_feedback WHERE tenant_id = ?");
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (fromMs != null) {
            sql.append(" AND created_at >= ?");
            args.add(fromMs);
        }
        if (toMs != null) {
            sql.append(" AND created_at <= ?");
            args.add(toMs);
        }
        sql.append(" ORDER BY created_at ASC LIMIT ?");
        args.add(limit);
        return jdbc.query(sql.toString(), MAPPER, args.toArray());
    }

    private static Thumb parseThumb(String s) {
        if (s == null || s.isBlank()) return null;
        return Thumb.valueOf(s);
    }

    private static Integer readRating(java.sql.ResultSet rs) throws java.sql.SQLException {
        int v = rs.getInt("rating");
        return rs.wasNull() ? null : v;
    }
}