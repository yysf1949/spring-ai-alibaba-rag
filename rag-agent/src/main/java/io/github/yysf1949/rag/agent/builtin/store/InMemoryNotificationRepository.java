package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.NotificationRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * InMemory 通知仓库 — 5 分钟去重窗口, 线程安全。
 */
@Component
@Profile("default")
public class InMemoryNotificationRepository implements NotificationRepositoryPort {

    /** userId -> list of records (最近优先) */
    private final Map<String, List<NotificationRecord>> store = new ConcurrentHashMap<>();

    @Override
    public NotificationRecord save(NotificationRecord record) {
        store.compute(record.userId(), (k, v) -> {
            if (v == null) v = new ArrayList<>();
            v.add(record);
            return v;
        });
        return record;
    }

    @Override
    public boolean existsByUserAndTemplateWithinWindow(
            String userId, String template, Duration window) {
        List<NotificationRecord> list = store.get(userId);
        if (list == null) return false;
        Instant cutoff = Instant.now().minus(window);
        return list.stream()
                .anyMatch(r -> r.template().equals(template) && r.sentAt().isAfter(cutoff));
    }

    @Override
    public Optional<NotificationRecord> findRecentByUserAndTemplate(
            String userId, String template) {
        List<NotificationRecord> list = store.get(userId);
        if (list == null) return Optional.empty();
        return list.stream()
                .filter(r -> r.template().equals(template))
                .reduce((first, second) -> second); // 最后一个 (最近)
    }
}