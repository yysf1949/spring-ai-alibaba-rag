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
            // Maintain secondary index: idempotencyKey → main key for O(1) lookup
            if (record.idempotencyKey() != null && !record.idempotencyKey().isEmpty()) {
                String idxKey = factory.key("price_protection_idx", record.tenantId(), record.idempotencyKey());
                factory.jedis().set(idxKey, key);
            }
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

    @Override
    public Optional<PriceProtectionRecord> findByIdempotencyKey(String idempotencyKey, String tenantId) {
        // O(1) lookup via secondary index instead of O(N) KEYS scan
        try {
            String idxKey = factory.key("price_protection_idx", tenantId, idempotencyKey);
            String mainKey = factory.jedis().get(idxKey);
            if (mainKey == null) {
                return Optional.empty();
            }
            var fields = factory.jedis().hgetAll(mainKey);
            if (fields == null || fields.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(new PriceProtectionRecord(
                    fields.get("claimId"), fields.get("tenantId"), fields.get("userId"),
                    fields.get("orderId"), fields.get("productId"),
                    Long.parseLong(fields.getOrDefault("refundAmountCents", "0")),
                    Long.parseLong(fields.getOrDefault("originalPriceCents", "0")),
                    Long.parseLong(fields.getOrDefault("currentPriceCents", "0")),
                    fields.get("status"), fields.get("reason"), fields.get("idempotencyKey")));
        } catch (Exception e) {
            throw new RuntimeException("Failed to find price protection by idempotency key", e);
        }
    }

    @Override
    public String nextClaimId() {
        return "PP-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

}