package io.github.yysf1949.rag.agent.builtin.port;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase 14: 站内通知仓库 Port — InMemory mock (生产替换为消息网关 / Redis Stream)。
 */
public interface NotificationRepositoryPort {
    NotificationRecord save(NotificationRecord record);
    boolean existsByUserAndTemplateWithinWindow(
            String userId, String template, java.time.Duration window);
    Optional<NotificationRecord> findRecentByUserAndTemplate(
            String userId, String template);

    record NotificationRecord(
            String notificationId,
            String tenantId,
            String userId,
            String template,
            String content,
            Instant sentAt
    ) { }

    static String newNotificationId() {
        return "NTF-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}