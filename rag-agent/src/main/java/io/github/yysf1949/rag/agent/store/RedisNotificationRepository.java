package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.NotificationRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.Collection;

@Component
@Profile("redis")
public class RedisNotificationRepository implements NotificationRepositoryPort {

    private final RedisStoreFactory factory;

    public RedisNotificationRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public NotificationRecord save(NotificationRecord record) {
        try {
            // Store notification as JSON
            String key = factory.key("notification", record.notificationId());
            String json = factory.mapper().writeValueAsString(record);
            factory.jedis().set(key, json);

            // Index in sorted set by userId, scored by sentAt epoch millis
            String zkey = factory.key("notifications", "user", record.userId());
            factory.jedis().zadd(zkey, record.sentAt().toEpochMilli(), record.notificationId());
            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save notification", e);
        }
    }

    @Override
    public boolean existsByUserAndTemplateWithinWindow(String userId, String template, Duration window) {
        try {
            String zkey = factory.key("notifications", "user", userId);
            long now = System.currentTimeMillis();
            long start = now - window.toMillis();

            Collection<String> ids = factory.jedis().zrangeByScore(zkey, start, now);
            for (String id : ids) {
                String key = factory.key("notification", id);
                String json = factory.jedis().get(key);
                if (json != null) {
                    NotificationRecord rec = factory.mapper().readValue(json, NotificationRecord.class);
                    if (template.equals(rec.template())) {
                        return true;
                    }
                }
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check notification existence", e);
        }
    }

    @Override
    public Optional<NotificationRecord> findRecentByUserAndTemplate(String userId, String template) {
        try {
            String zkey = factory.key("notifications", "user", userId);
            // Get the most recent notification id (highest score)
            Collection<String> ids = factory.jedis().zrevrange(zkey, 0, -1);
            for (String id : ids) {
                String key = factory.key("notification", id);
                String json = factory.jedis().get(key);
                if (json != null) {
                    NotificationRecord rec = factory.mapper().readValue(json, NotificationRecord.class);
                    if (template.equals(rec.template())) {
                        return Optional.of(rec);
                    }
                }
            }
            return Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to find recent notification", e);
        }
    }
}
