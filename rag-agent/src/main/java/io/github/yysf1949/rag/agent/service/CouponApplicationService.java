package io.github.yysf1949.rag.agent.service;

import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.CouponRepositoryPort.CouponRecord;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 优惠券领域服务 — Agent 工具和管理后台共用的唯一优惠券入口。
 *
 * <p>对齐「路条编程」文章核心论点：「Agent 应该调用和管理后台相同的领域服务」
 * 「不应该有一套给 Agent 用的简化逻辑，另一套给管理后台用的完整逻辑」。</p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>金额上限校验 — 单张 ≤200 元</li>
 *   <li>幂等保护 — 委托 {@link IdempotencyStore}</li>
 *   <li>指标埋点 — 业务失败时记录 {@link AgentMetrics#recordBusinessError}</li>
 * </ul>
 */
@Component
public class CouponApplicationService {

    /** 单张优惠券金额上限（分）— 200 元 */
    public static final long ISSUE_MAX_AMOUNT_CENTS = 200_00L;

    private final CouponRepositoryPort couponRepository;
    private final IdempotencyStore idempotencyStore;
    private final AgentMetrics agentMetrics;

    public CouponApplicationService(CouponRepositoryPort couponRepository,
                                    IdempotencyStore idempotencyStore,
                                    AgentMetrics agentMetrics) {
        this.couponRepository = couponRepository;
        this.idempotencyStore = idempotencyStore;
        this.agentMetrics = agentMetrics;
    }

    /**
     * 补发优惠券。
     *
     * <ol>
     *   <li>金额上限校验 — >200 元记录 business_error 并抛异常</li>
     *   <li>幂等检查 — 重复 key 返回已有结果</li>
     *   <li>创建优惠券，回填幂等结果</li>
     * </ol>
     */
    public CouponRecord issueCoupon(String tenantId, String userId, String orderId,
                                    long amountCents, String reasonTag,
                                    IdempotencyKey idempotencyKey) {
        // 金额门控
        if (amountCents > ISSUE_MAX_AMOUNT_CENTS) {
            agentMetrics.recordBusinessError("issue_coupon", "AMOUNT_EXCEEDED");
            throw new AmountLimitExceededException("issue_coupon", amountCents, ISSUE_MAX_AMOUNT_CENTS);
        }

        // 幂等检查
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            String existingId = (String) put.value();
            if (existingId != null) {
                // 返回已有的优惠券（幂等回放）
                return couponRepository.findActiveByTenantAndUser(tenantId, userId)
                        .stream()
                        .filter(c -> c.couponId().equals(existingId))
                        .findFirst()
                        .orElse(null);
            }
        }

        // 创建优惠券
        var coupon = new CouponRecord(
                CouponRepositoryPort.newCouponId(),
                tenantId, userId, orderId,
                amountCents, reasonTag, "ACTIVE");
        CouponRecord saved = couponRepository.save(coupon);

        // 回填幂等结果
        idempotencyStore.replace(idempotencyKey, saved.couponId());
        return saved;
    }

    /**
     * 查询用户当前可用的优惠券列表。
     */
    public List<CouponRecord> listActiveCoupons(String tenantId, String userId) {
        return couponRepository.findActiveByTenantAndUser(tenantId, userId);
    }
}
