package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.ComplaintRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@Profile("redis")
public class RedisComplaintRepository implements ComplaintRepositoryPort {

    private final RedisStoreFactory factory;

    public RedisComplaintRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public ComplaintRecord save(ComplaintRecord complaint) {
        try {
            String key = factory.key("complaint", complaint.tenantId(), complaint.complaintId());
            String json = factory.mapper().writeValueAsString(complaint);
            factory.jedis().set(key, json);
            return complaint;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save complaint", e);
        }
    }

    @Override
    public Optional<ComplaintRecord> findByIdAndTenant(String complaintId, String tenantId) {
        try {
            String key = factory.key("complaint", tenantId, complaintId);
            String json = factory.jedis().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(factory.mapper().readValue(json, ComplaintRecord.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find complaint", e);
        }
    }
}
