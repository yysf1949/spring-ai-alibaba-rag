package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.PriceProtectionPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

@Component
@Profile("redis")
public class RedisPriceProtectionRepository implements PriceProtectionPort {

    private static final int DEFAULT_PROTECTION_DAYS = 7;
    private static final double DEFAULT_MAX_REFUND_RATIO = 1.0;

    private final RedisStoreFactory factory;

    public RedisPriceProtectionRepository(RedisStoreFactory factory) {
        this.factory = factory;
    }

    @Override
    public PriceProtectionRecord save(PriceProtectionRecord record) {
        try {
            String key = factory.key("price_protection", record.tenantId(), record.claimId());
            Map<String, String> hash = new HashMap<>();
            hash.put("claimId", record.claimId());
            hash.put("tenantId", record.tenantId());
            hash.put("userId", record.userId());
            hash.put("orderId", record.orderId());
            hash.put("productId", record.productId());
            hash.put("refundAmountCents", String.valueOf(record.refundAmountCents()));
            hash.put("originalPriceCents", String.valueOf(record.originalPriceCents()));
            hash.put("currentPriceCents", String.valueOf(record.currentPriceCents()));
            hash.put("status", record.status());
            hash.put("reason", record.reason() == null ? "" : record.reason());
            hash.put("idempotencyKey", record.idempotencyKey() == null ? "" : record.idempotencyKey());
            factory.jedis().hset(key, hash);
            return record;
        } catch (Exception e) {
            throw new RuntimeException("Failed to save price protection record", e);
        }
    }

    @Override
    public Optional<PriceProtectionRecord> findByIdAndTenant(String claimId, String tenantId) {
        try {
            String key = factory.key("price_protection", tenantId, claimId);
            Map<String, String> hash = factory.jedis().hgetAll(key);
            if (hash == null || hash.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PriceProtectionRecord(
                    hash.get("claimId"),
                    hash.get("tenantId"),
                    hash.get("userId"),
                    hash.get("orderId"),
                    hash.get("productId"),
                    Long.parseLong(hash.getOrDefault("refundAmountCents", "0")),
                    Long.parseLong(hash.getOrDefault("originalPriceCents", "0")),
                    Long.parseLong(hash.getOrDefault("currentPriceCents", "0")),
                    hash.getOrDefault("status", "PENDING"),
                    hash.get("reason"),
                    hash.get("idempotencyKey")
            ));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find price protection record", e);
        }
    }

    @Override
    public PriceProtectionPolicy getPolicy(String productCategory) {
        return new PriceProtectionPolicy(DEFAULT_PROTECTION_DAYS, DEFAULT_MAX_REFUND_RATIO);
    }

    @Override
    public boolean isWithinProtectionPeriod(String orderTimeStr, String productCategory) {
        try {
            LocalDate orderDate = LocalDate.parse(orderTimeStr, DateTimeFormatter.ISO_LOCAL_DATE);
            long daysBetween = ChronoUnit.DAYS.between(orderDate, LocalDate.now());
            return daysBetween <= DEFAULT_PROTECTION_DAYS;
        } catch (Exception e) {
            return false;
        }
    }
}
