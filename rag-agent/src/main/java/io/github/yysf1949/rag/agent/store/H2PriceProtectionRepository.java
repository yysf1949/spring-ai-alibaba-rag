package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.PriceProtectionPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

@Component
@Profile("h2")
public class H2PriceProtectionRepository implements PriceProtectionPort {

    private static final int DEFAULT_PROTECTION_DAYS = 7;
    private static final double DEFAULT_MAX_REFUND_RATIO = 1.0;

    private final JdbcTemplate jdbc;
    private static final RowMapper<PriceProtectionRecord> MAPPER = (rs, row) -> new PriceProtectionRecord(
            rs.getString("claim_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("order_id"),
            rs.getString("product_id"),
            rs.getLong("refund_amount_cents"),
            rs.getLong("original_price_cents"),
            rs.getLong("current_price_cents"),
            rs.getString("status"),
            rs.getString("reason"),
            rs.getString("idempotency_key")
    );

    public H2PriceProtectionRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public PriceProtectionRecord save(PriceProtectionRecord record) {
        jdbc.update("MERGE INTO agent_price_protection (claim_id, tenant_id, user_id, order_id, product_id, refund_amount_cents, original_price_cents, current_price_cents, status, reason, idempotency_key) "
                        + "KEY(claim_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                record.claimId(), record.tenantId(), record.userId(),
                record.orderId(), record.productId(), record.refundAmountCents(),
                record.originalPriceCents(), record.currentPriceCents(),
                record.status(), record.reason(), record.idempotencyKey());
        return record;
    }

    @Override
    public Optional<PriceProtectionRecord> findByIdAndTenant(String claimId, String tenantId) {
        return jdbc.query("SELECT * FROM agent_price_protection WHERE claim_id = ? AND tenant_id = ?",
                MAPPER, claimId, tenantId).stream().findFirst();
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
        try {
            var list = jdbc.query(
                    "SELECT * FROM agent_price_protection WHERE idempotency_key = ? AND tenant_id = ?",
                    MAPPER, idempotencyKey, tenantId);
            return list.stream().findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    @Override
    public String nextClaimId() {
        return "PP-" + java.util.UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

}