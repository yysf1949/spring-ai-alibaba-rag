package io.github.yysf1949.rag.agent.handoff;

import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 内存版待人工处理队列。
 *
 * <h2>生产替代</h2>
 * <p>真实生产应该走工单系统（Jira/自研）或客服系统（Zendesk）。本类内存版
 * 满足 demo + 集成测试用。Phase 11 可以加 {@code WorkOrderQueueAdapter} 实现。</p>
 */
@Component
public class HumanReviewQueue {

    private final Map<String, QueueItem> store = new ConcurrentHashMap<>();

    public QueueItem enqueue(QueueItem item) {
        store.put(item.handoffId(), item);
        return item;
    }

    public Optional<QueueItem> complete(String handoffId, String resolution,
                                        String resolvedBy, String note) {
        QueueItem item = store.remove(handoffId);
        if (item == null) return Optional.empty();
        return Optional.of(new QueueItem(
                item.handoffId(), item.context(), item.tenantId(),
                resolution, resolvedBy, note, item.enqueuedAt(), Instant.now().toString()));
    }

    public List<QueueItem> listPending(String tenantId) {
        return store.values().stream()
                .filter(i -> i.tenantId().equals(tenantId))
                .filter(i -> i.resolution() == null)
                .collect(Collectors.toList());
    }

    public record QueueItem(
            String handoffId,
            HandoffContext context,
            String tenantId,
            String resolution,    // null 表示 pending
            String resolvedBy,    // null 表示 pending
            String resolutionNote,
            String enqueuedAt,
            String resolvedAt
    ) { }
}