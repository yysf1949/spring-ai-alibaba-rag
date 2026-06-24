package io.github.yysf1949.rag.agent.feedback.store;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.Thumb;
import io.github.yysf1949.rag.agent.store.RedisStoreFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Redis 持久化反馈仓库 — {@code @Profile("redis")} 激活。
 *
 * <p>键设计：
 * <ul>
 *   <li>{@code feedback:{feedbackId}} — Hash, 单条反馈完整字段</li>
 *   <li>{@code feedback:tenant:{tenantId}} — Sorted Set, score=createdAt, member=feedbackId</li>
 *   <li>{@code feedback:conversation:{tenantId}:{conversationId}} — Sorted Set, 同上</li>
 * </ul>
 * </p>
 */
@Component
@Profile("redis")
public class RedisFeedbackRepository implements FeedbackPort {

    private final RedisStoreFactory factory;

    public RedisFeedbackRepository(RedisStoreFactory factory) { this.factory = factory; }

    @Override
    public FeedbackRecord save(FeedbackRecord record) {
        try {
            String key = factory.key("feedback", record.feedbackId());
            Map<String, String> hash = new HashMap<>();
            hash.put("feedbackId", record.feedbackId());
            hash.put("tenantId", record.tenantId());
            hash.put("userId", record.userId());
            hash.put("conversationId", record.conversationId());
            if (record.messageId() != null) hash.put("messageId", record.messageId());
            if (record.thumb() != null) hash.put("thumb", record.thumb().name());
            if (record.rating() != null) hash.put("rating", String.valueOf(record.rating()));
            if (record.comment() != null) hash.put("comment", record.comment());
            hash.put("sourceChannel", record.sourceChannel());
            if (record.kbVersion() != null) hash.put("kbVersion", record.kbVersion());
            hash.put("createdAt", String.valueOf(record.createdAt()));
            factory.jedis().hset(key, hash);

            factory.jedis().zadd(
                    factory.key("feedback:by-tenant", record.tenantId()),
                    record.createdAt(),
                    record.feedbackId()
            );
            factory.jedis().zadd(
                    factory.key("feedback:by-conv", record.tenantId(), record.conversationId()),
                    record.createdAt(),
                    record.feedbackId()
            );
            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save feedback", e);
        }
    }

    @Override
    public Optional<FeedbackRecord> findById(String tenantId, String feedbackId) {
        try {
            String key = factory.key("feedback", feedbackId);
            Map<String, String> hash = factory.jedis().hgetAll(key);
            if (hash == null || hash.isEmpty()) return Optional.empty();
            FeedbackRecord r = hydrate(hash);
            return r.tenantId().equals(tenantId) ? Optional.of(r) : Optional.empty();
        } catch (Exception e) {
            throw new RuntimeException("Failed to read feedback", e);
        }
    }

    @Override
    public List<FeedbackRecord> findByConversation(String tenantId, String conversationId) {
        try {
            String zsetKey = factory.key("feedback:by-conv", tenantId, conversationId);
            List<String> ids = factory.jedis().zrange(zsetKey, 0, -1);
            if (ids == null || ids.isEmpty()) return List.of();
            List<FeedbackRecord> out = new ArrayList<>();
            for (String id : ids) {
                Map<String, String> hash = factory.jedis().hgetAll(factory.key("feedback", id));
                if (hash != null && !hash.isEmpty()) out.add(hydrate(hash));
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to query feedback by conversation", e);
        }
    }

    @Override
    public List<FeedbackRecord> findByTenant(String tenantId, int limit) {
        try {
            String zsetKey = factory.key("feedback:by-tenant", tenantId);
            List<String> ids = factory.jedis().zrange(zsetKey, 0, limit - 1);
            if (ids == null || ids.isEmpty()) return List.of();
            List<FeedbackRecord> out = new ArrayList<>();
            for (String id : ids) {
                Map<String, String> hash = factory.jedis().hgetAll(factory.key("feedback", id));
                if (hash != null && !hash.isEmpty()) out.add(hydrate(hash));
            }
            return out;
        } catch (Exception e) {
            throw new RuntimeException("Failed to query feedback by tenant", e);
        }
    }

    @Override
    public long countByTenant(String tenantId) {
        try {
            return factory.jedis().zcard(factory.key("feedback:by-tenant", tenantId));
        } catch (Exception e) {
            throw new RuntimeException("Failed to count feedback", e);
        }
    }

    private static FeedbackRecord hydrate(Map<String, String> hash) {
        Integer rating = hash.get("rating") == null ? null : Integer.parseInt(hash.get("rating"));
        return new FeedbackRecord(
                hash.get("feedbackId"),
                hash.get("tenantId"),
                hash.get("userId"),
                hash.get("conversationId"),
                hash.get("messageId"),
                hash.get("thumb") == null ? null : Thumb.valueOf(hash.get("thumb")),
                rating,
                hash.get("comment"),
                hash.get("sourceChannel"),
                hash.get("kbVersion"),
                Long.parseLong(hash.get("createdAt"))
        );
    }
}