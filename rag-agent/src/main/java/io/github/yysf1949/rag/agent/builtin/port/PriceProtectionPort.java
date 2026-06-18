package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

/**
 * 价保存储 Port — 价保申请记录的持久化抽象。
 *
 * <h2>Phase 12: 电商特价管理</h2>
 * <p>当前仅提供 InMemory 实现。若需 Redis/H2 持久化，遵循 Phase 11 模式扩展。</p>
 */
public interface PriceProtectionPort {

    PriceProtectionRecord save(PriceProtectionRecord record);

    Optional<PriceProtectionRecord> findByIdAndTenant(String claimId, String tenantId);

    /** 查询某商品品类当前的价保政策（天数 + 赔付比例上限）。 */
    PriceProtectionPolicy getPolicy(String productCategory);

    /** 判断某订单时间是否仍在价保期内。 */
    boolean isWithinProtectionPeriod(String orderTimeStr, String productCategory);

    /**
     * 价保申请记录。
     *
     * @param claimId            申请单 ID
     * @param tenantId           租户
     * @param userId             用户
     * @param orderId            原始订单
     * @param productId          商品
     * @param refundAmountCents  应退差价（分）
     * @param originalPriceCents 购买时价格（分）
     * @param currentPriceCents  当前价格（分）
     * @param status             PENDING / APPROVED / REJECTED
     * @param reason             价保原因
     */
    record PriceProtectionRecord(
            String claimId, String tenantId, String userId, String orderId,
            String productId, long refundAmountCents, long originalPriceCents,
            long currentPriceCents, String status, String reason, String idempotencyKey) {

        /** 创建待审批价保申请。 */
        public static PriceProtectionRecord pending(
                String claimId, String tenantId, String userId,
                String orderId, String productId,
                long refundAmountCents, long originalPriceCents,
                long currentPriceCents, String reason, String idempotencyKey) {
            return new PriceProtectionRecord(claimId, tenantId, userId, orderId, productId,
                    refundAmountCents, originalPriceCents, currentPriceCents, "PENDING", reason, idempotencyKey);
        }
    }

    /**
     * 价保政策。
     *
     * @param protectionDays 价保天数（例：7 = 7 天内可申请）
     * @param maxRefundRatio 最大退款比例（例：1.0 = 可退全额差价，0.5 = 退一半）
     */
    record PriceProtectionPolicy(int protectionDays, double maxRefundRatio) {}
}
