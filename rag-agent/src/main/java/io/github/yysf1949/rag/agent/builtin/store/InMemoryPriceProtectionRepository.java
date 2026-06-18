package io.github.yysf1949.rag.agent.builtin.store;

import io.github.yysf1949.rag.agent.builtin.port.PriceProtectionPort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 价保申请 InMemory 实现（默认存储）。
 *
 * <p>默认价保政策：7 天 / 全额差价赔付。</p>
 */
@Component
public class InMemoryPriceProtectionRepository implements PriceProtectionPort {

    private static final PriceProtectionPolicy DEFAULT_POLICY =
            new PriceProtectionPolicy(7, 1.0);

    private final ConcurrentHashMap<String, PriceProtectionRecord> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> keyToClaimId = new ConcurrentHashMap<>();
    private final AtomicLong idSeq = new AtomicLong(0);

    @Override
    public PriceProtectionRecord save(PriceProtectionRecord record) {
        store.put(record.claimId(), record);
        keyToClaimId.put(record.idempotencyKey(), record.claimId());
        return record;
    }

    @Override
    public Optional<PriceProtectionRecord> findByIdAndTenant(String claimId, String tenantId) {
        return Optional.ofNullable(store.get(claimId))
                .filter(r -> r.tenantId().equals(tenantId));
    }

    /** 根据幂等键查找已存在的申请。 */
    public Optional<PriceProtectionRecord> findByIdempotencyKey(String key, String tenantId) {
        return Optional.ofNullable(keyToClaimId.get(key))
                .map(store::get)
                .filter(r -> r.tenantId().equals(tenantId));
    }

    @Override
    public PriceProtectionPolicy getPolicy(String productCategory) {
        return DEFAULT_POLICY;
    }

    @Override
    public boolean isWithinProtectionPeriod(String orderTimeStr, String productCategory) {
        try {
            var orderTime = Instant.parse(orderTimeStr);
            var now = Instant.now();
            var days = Duration.between(orderTime, now).toDays();
            return days <= DEFAULT_POLICY.protectionDays() && days >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** 生成唯一 ID 格式：PP-{seq} */
    public String nextClaimId() {
        return "PP-" + idSeq.incrementAndGet();
    }
}
