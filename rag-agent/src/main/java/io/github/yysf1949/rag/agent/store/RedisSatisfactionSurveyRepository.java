package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Profile("redis")
public class RedisSatisfactionSurveyRepository implements SatisfactionSurveyPort {

    private final RedisStoreFactory factory;

    public RedisSatisfactionSurveyRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public SurveyRecord save(SurveyRecord record) {
        try {
            String key = factory.key("survey", record.surveyId());
            Map<String, String> hash = Map.of(
                    "surveyId", record.surveyId(),
                    "tenantId", record.tenantId(),
                    "userId", record.userId(),
                    "conversationId", record.conversationId(),
                    "rating", String.valueOf(record.rating()),
                    "feedback", record.feedback() == null ? "" : record.feedback(),
                    "resolved", String.valueOf(record.resolved()),
                    "createdAt", String.valueOf(record.createdAt())
            );
            factory.jedis().hset(key, hash);

            String zsetKey = factory.key("surveys", "conversation", record.conversationId());
            factory.jedis().zadd(zsetKey, record.createdAt(), record.surveyId());

            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save satisfaction survey", e);
        }
    }

    @Override
    public List<SurveyRecord> findByConversation(String conversationId) {
        try {
            String zsetKey = factory.key("surveys", "conversation", conversationId);
            List<String> surveyIds = factory.jedis().zrange(zsetKey, 0, -1);
            if (surveyIds == null || surveyIds.isEmpty()) {
                return List.of();
            }
            List<SurveyRecord> result = new ArrayList<>();
            for (String surveyId : surveyIds) {
                String key = factory.key("survey", surveyId);
                Map<String, String> hash = factory.jedis().hgetAll(key);
                if (hash != null && !hash.isEmpty()) {
                    result.add(new SurveyRecord(
                            hash.get("surveyId"),
                            hash.get("tenantId"),
                            hash.get("userId"),
                            hash.get("conversationId"),
                            Integer.parseInt(hash.getOrDefault("rating", "0")),
                            hash.getOrDefault("feedback", ""),
                            Boolean.parseBoolean(hash.getOrDefault("resolved", "false")),
                            Long.parseLong(hash.getOrDefault("createdAt", "0"))
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to find surveys by conversation", e);
        }
    }

    @Override
    public long countAll() {
        try {
            var keys = factory.jedis().keys(factory.key("survey", "*"));
            return keys == null ? 0 : keys.size();
        } catch (Exception e) {
            return 0;
        }
    }

    @Override
    public long countResolved() {
        try {
            var keys = factory.jedis().keys(factory.key("survey", "*"));
            if (keys == null) return 0;
            long resolved = 0;
            for (String key : keys) {
                String val = factory.jedis().hget(key, "resolved");
                if (Boolean.parseBoolean(val)) {
                    resolved++;
                }
            }
            return resolved;
        } catch (Exception e) {
            return 0;
        }
    }
}
