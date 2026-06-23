package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.NotificationRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

@Component
@Profile("h2")
public class H2NotificationRepository implements NotificationRepositoryPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<NotificationRecord> MAPPER = (rs, row) -> new NotificationRecord(
            rs.getString("notification_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("template"),
            rs.getString("content"),
            Instant.ofEpochMilli(rs.getLong("sent_at"))
    );

    public H2NotificationRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public NotificationRecord save(NotificationRecord record) {
        jdbc.update("MERGE INTO agent_notification (notification_id, tenant_id, user_id, template, content, sent_at) "
                        + "KEY(notification_id) VALUES (?, ?, ?, ?, ?, ?)",
                record.notificationId(), record.tenantId(), record.userId(),
                record.template(), record.content(), record.sentAt().toEpochMilli());
        return record;
    }

    @Override
    public boolean existsByUserAndTemplateWithinWindow(String userId, String template, Duration window) {
        long cutoff = Instant.now().minus(window).toEpochMilli();
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_notification WHERE user_id = ? AND template = ? AND sent_at > ?",
                Integer.class, userId, template, cutoff);
        return count != null && count > 0;
    }

    @Override
    public Optional<NotificationRecord> findRecentByUserAndTemplate(String userId, String template) {
        return jdbc.query("SELECT * FROM agent_notification WHERE user_id = ? AND template = ? ORDER BY sent_at DESC LIMIT 1",
                MAPPER, userId, template).stream().findFirst();
    }
}
