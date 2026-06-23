package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;

@Component
@Profile("redis")
public class RedisRefundRepository implements RefundRepositoryPort {

    private final RedisStoreFactory factory;

    public RedisRefundRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public RefundRecord save(RefundRecord refund) {
        try {
            String key = factory.key("refund", refund.tenantId(), refund.refundId());
            String json = factory.mapper().writeValueAsString(refund);
            factory.jedis().set(key, json);
            return refund;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save refund", e);
        }
    }

    @Override
    public Optional<RefundRecord> findByIdAndTenant(String refundId, String tenantId) {
        try {
            String key = factory.key("refund", tenantId, refundId);
            String json = factory.jedis().get(key);
            if (json == null) {
                return Optional.empty();
            }
            return Optional.of(factory.mapper().readValue(json, RefundRecord.class));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find refund", e);
        }
    }

    @Override
    public int count() {
        String prefix = factory.entityPrefix("refund");
        Set<String> keys = factory.jedis().keys(prefix + "*");
        return keys != null ? keys.size() : 0;
    }
}
