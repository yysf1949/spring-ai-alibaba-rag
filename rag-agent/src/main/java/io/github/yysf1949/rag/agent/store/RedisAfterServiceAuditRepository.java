package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.AfterServiceAuditPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Component
@Profile("redis")
public class RedisAfterServiceAuditRepository implements AfterServiceAuditPort {

    private final RedisStoreFactory factory;

    public RedisAfterServiceAuditRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public AuditRecord save(AuditRecord record) {
        try {
            String key = factory.key("audit", record.auditId());
            String stepsJson = factory.mapper().writeValueAsString(record.steps() == null ? List.of() : record.steps());
            Map<String, String> hash = Map.of(
                    "auditId", record.auditId(),
                    "orderId", record.orderId(),
                    "actionType", record.actionType(),
                    "steps", stepsJson,
                    "success", String.valueOf(record.success()),
                    "createdAt", String.valueOf(record.createdAt())
            );
            factory.jedis().hset(key, hash);

            String zsetKey = factory.key("audits", "order", record.orderId());
            factory.jedis().zadd(zsetKey, record.createdAt(), record.auditId());

            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save audit record", e);
        }
    }

    @Override
    public List<AuditRecord> findByOrder(String orderId) {
        try {
            String zsetKey = factory.key("audits", "order", orderId);
            List<String> auditIds = factory.jedis().zrange(zsetKey, 0, -1);
            if (auditIds == null || auditIds.isEmpty()) {
                return List.of();
            }
            List<AuditRecord> result = new ArrayList<>();
            for (String auditId : auditIds) {
                String key = factory.key("audit", auditId);
                Map<String, String> hash = factory.jedis().hgetAll(key);
                if (hash != null && !hash.isEmpty()) {
                    String stepsRaw = hash.getOrDefault("steps", "[]");
                    List<String> steps = factory.mapper().readValue(stepsRaw,
                            factory.mapper().getTypeFactory().constructCollectionType(List.class, String.class));
                    result.add(new AuditRecord(
                            hash.get("auditId"),
                            hash.get("orderId"),
                            hash.get("actionType"),
                            steps,
                            Boolean.parseBoolean(hash.getOrDefault("success", "false")),
                            Long.parseLong(hash.getOrDefault("createdAt", "0"))
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            throw new RuntimeException("Failed to find audits by order", e);
        }
    }
}
