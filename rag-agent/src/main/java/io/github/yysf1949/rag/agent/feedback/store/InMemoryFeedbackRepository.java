package io.github.yysf1949.rag.agent.feedback.store;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 反馈内存仓库 — 教学 demo + 默认 profile。
 *
 * <p>用 {@code ConcurrentHashMap} 按 feedbackId 索引。租户过滤在调用层完成。
 * 数据不持久化，重启清空 — 适合 dev/test。生产用 H2 或 Redis 实现。</p>
 */
@Component
@Profile("default")
public class InMemoryFeedbackRepository implements FeedbackPort {

    private final Map<String, FeedbackRecord> store = new ConcurrentHashMap<>();

    @Override
    public FeedbackRecord save(FeedbackRecord record) {
        store.put(record.feedbackId(), record);
        return record;
    }

    @Override
    public Optional<FeedbackRecord> findById(String tenantId, String feedbackId) {
        FeedbackRecord r = store.get(feedbackId);
        if (r != null && r.tenantId().equals(tenantId)) {
            return Optional.of(r);
        }
        return Optional.empty();
    }

    @Override
    public List<FeedbackRecord> findByConversation(String tenantId, String conversationId) {
        List<FeedbackRecord> out = new ArrayList<>();
        for (FeedbackRecord r : store.values()) {
            if (r.tenantId().equals(tenantId) && r.conversationId().equals(conversationId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(FeedbackRecord::createdAt));
        return out;
    }

    @Override
    public List<FeedbackRecord> findByTenant(String tenantId, int limit) {
        List<FeedbackRecord> out = new ArrayList<>();
        for (FeedbackRecord r : store.values()) {
            if (r.tenantId().equals(tenantId)) {
                out.add(r);
            }
        }
        out.sort(Comparator.comparingLong(FeedbackRecord::createdAt));
        if (out.size() > limit) {
            return out.subList(0, limit);
        }
        return out;
    }

    @Override
    public long countByTenant(String tenantId) {
        long n = 0;
        for (FeedbackRecord r : store.values()) {
            if (r.tenantId().equals(tenantId)) {
                n++;
            }
        }
        return n;
    }
}