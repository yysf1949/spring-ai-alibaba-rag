package io.github.yysf1949.rag.agent.service;

import io.github.yysf1949.rag.agent.builtin.PaymentChannelTool;
import io.github.yysf1949.rag.agent.builtin.RefundRuleTool;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort;
import io.github.yysf1949.rag.agent.builtin.port.RefundRepositoryPort.RefundRecord;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

/**
 * 退款领域服务 — Agent 工具和管理后台共用的唯一退款入口。
 *
 * <p>对齐「路条编程」文章核心论点：「Agent 应该调用和管理后台相同的领域服务」
 * 「不应该有一套给 Agent 用的简化逻辑，另一套给管理后台用的完整逻辑」。</p>
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>业务规则前置校验（退款期、组合优惠、支付渠道）— 委托 {@link RefundRuleTool}</li>
 *   <li>幂等保护 — 委托 {@link IdempotencyStore}</li>
 *   <li>金额门控 — ≤500 元自动审批，>500 元需人工</li>
 *   <li>指标埋点 — 业务失败时记录 {@link AgentMetrics#recordBusinessError}</li>
 * </ul>
 */
@Component
public class RefundApplicationService {

    /** 创建退款单笔金额上限（分）— 500 元 */
    public static final long CREATE_MAX_AMOUNT_CENTS = 500_00L;

    private final RefundRepositoryPort refundRepository;
    private final RefundRuleTool refundRuleTool;
    private final IdempotencyStore idempotencyStore;
    private final AgentMetrics agentMetrics;

    public RefundApplicationService(RefundRepositoryPort refundRepository,
                                    RefundRuleTool refundRuleTool,
                                    IdempotencyStore idempotencyStore,
                                    AgentMetrics agentMetrics) {
        this.refundRepository = refundRepository;
        this.refundRuleTool = refundRuleTool;
        this.idempotencyStore = idempotencyStore;
        this.agentMetrics = agentMetrics;
    }

    /**
     * 创建退款申请。
     *
     * <ol>
     *   <li>金额上限校验 — >500 元记录 business_error 并抛异常</li>
     *   <li>幂等检查 — 重复 key 返回已有结果</li>
     *   <li>业务规则校验 — 退款期 / 组合优惠 / 支付渠道</li>
     *   <li>≤500 元自动审批（PENDING），>500 元需人工</li>
     * </ol>
     */
    public RefundRecord createRefund(String tenantId, String userId, String orderId,
                                     long amountCents, String reason,
                                     IdempotencyKey idempotencyKey) {
        // 金额门控
        if (amountCents > CREATE_MAX_AMOUNT_CENTS) {
            agentMetrics.recordBusinessError("create_refund", "AMOUNT_EXCEEDED");
            throw new AmountLimitExceededException("create_refund", amountCents, CREATE_MAX_AMOUNT_CENTS);
        }

        // 幂等检查
        if (idempotencyKey != null) {
            IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
            if (put.isReplay()) {
                String existingId = (String) put.value();
                if (existingId != null) {
                    return refundRepository.findByIdAndTenant(existingId, tenantId).orElse(null);
                }
            }
        }

        // 业务规则前置校验 — 退款期 / 组合优惠 / 支付渠道
        RefundRuleTool.RefundRuleResult rule = refundRuleTool.checkRefundRules(
                new RefundRuleTool.CheckRefundRulesRequest(orderId));
        if (rule.requiresManual()) {
            agentMetrics.recordBusinessError("create_refund", "BUSINESS_RULE_BLOCKED");
            throw new HandoffRequiredException(
                    "create_refund",
                    rule.reason(),
                    rule.matchedRules(),
                    "Order [" + orderId + "] hits business rule " + rule.reason()
                            + "; refund requires manual review per company policy.");
        }

        // 创建退款记录 — 金额 ≤500 自动审批（PENDING 状态，无需人工介入）
        var refund = new RefundRecord(
                RefundRepositoryPort.newRefundId(),
                tenantId, userId, orderId,
                amountCents, reason, "PENDING");
        RefundRecord saved = refundRepository.save(refund);

        // 回填幂等结果
        if (idempotencyKey != null) {
            idempotencyStore.replace(idempotencyKey, saved.refundId());
        }
        return saved;
    }

    /**
     * 审批退款申请。
     *
     * <p>幂等：已 APPROVED 的退款单重复审批直接返回当前状态。</p>
     */
    public RefundRecord approveRefund(String refundId, String tenantId, String adminId) {
        RefundRecord existing = refundRepository.findByIdAndTenant(refundId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Refund not found"));

        // 幂等：已 APPROVED 直接返回
        if ("APPROVED".equals(existing.status())) {
            return existing;
        }

        var approved = new RefundRecord(
                existing.refundId(), existing.tenantId(), existing.userId(),
                existing.orderId(), existing.amountCents(), existing.reason(), "APPROVED");
        return refundRepository.save(approved);
    }

    /**
     * 取消退款申请。
     */
    public RefundRecord cancelRefund(String refundId, String tenantId, String reason) {
        RefundRecord existing = refundRepository.findByIdAndTenant(refundId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Refund not found"));

        // 幂等：已 CANCELLED 直接返回
        if ("CANCELLED".equals(existing.status())) {
            return existing;
        }

        var cancelled = new RefundRecord(
                existing.refundId(), existing.tenantId(), existing.userId(),
                existing.orderId(), existing.amountCents(), reason, "CANCELLED");
        return refundRepository.save(cancelled);
    }
}
