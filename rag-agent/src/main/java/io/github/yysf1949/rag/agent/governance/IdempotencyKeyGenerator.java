package io.github.yysf1949.rag.agent.governance;

import java.util.UUID;

/**
 * 语义化幂等键构造器 — 在已有 {@link IdempotencyKey} 之上提供业务友好的快捷方法。
 *
 * <h2>对齐「路条编程」文章 §"幂等是 AI 客服第一课"</h2>
 * <p>"每一个写操作工具都应该接收一个稳定的业务幂等键，由会话 ID、用户确认动作
 * 和业务对象共同生成"。本类不取代 {@link IdempotencyKey#of}，只提供语义化包装：
 * 调用方写 {@code IdempotencyKeyGenerator.forCancelOrder(sessionId, orderId)}
 * 而不是手动拼接 token，让"幂等键 = 业务意图 + 业务对象"显式化。</p>
 *
 * <h2>token 生成规则</h2>
 * <p>token = {@code "<action>:<businessObjectId>:<stableToken>"}，其中 stableToken 由
 * 调用方传入（前端按钮点击/用户确认动作），未传时回退 {@link UUID#randomUUID()}。</p>
 *
 * <h2>为什么是 wrapper 而不是新建类</h2>
 * <p>底层 SHA-256 + 五元组拼接逻辑已在 {@link IdempotencyKey} 验证（Phase 10）；
 * 本类只追加"按业务语义自动填 token"一层，不引入新的 hash 算法。</p>
 */
public final class IdempotencyKeyGenerator {

    private IdempotencyKeyGenerator() { }

    /**
     * 通用模板 — action 名（如 {@code cancelOrder}） + businessObjectId（如 {@code orderId}）
     * + 外部 stableToken（按钮点击/确认动作 ID）。
     */
    public static IdempotencyKey forAction(AgentIdentity identity, String toolName,
                                            String action, String businessObjectId,
                                            String stableToken) {
        String token = action + ":" + businessObjectId + ":" + requireToken(stableToken);
        return IdempotencyKey.of(
                identity.tenantId(), identity.userId(), identity.sessionId(),
                toolName, token);
    }

    /** 取消订单 — action=cancelOrder */
    public static IdempotencyKey forCancelOrder(AgentIdentity identity, String orderId,
                                                 String stableToken) {
        return forAction(identity, "cancel_order", "cancelOrder", orderId, stableToken);
    }

    /** 申请退款 — action=createRefund + reason 拼进 token */
    public static IdempotencyKey forCreateRefund(AgentIdentity identity, String orderId,
                                                  String reason, String stableToken) {
        String token = "createRefund:" + orderId + ":" + sanitize(reason) + ":" + requireToken(stableToken);
        return IdempotencyKey.of(
                identity.tenantId(), identity.userId(), identity.sessionId(),
                "create_refund", token);
    }

    /** 审批退款 — L4 admin */
    public static IdempotencyKey forApproveRefund(AgentIdentity identity, String refundId,
                                                   String stableToken) {
        return forAction(identity, "approve_refund", "approveRefund", refundId, stableToken);
    }

    /** 发放优惠券 — action=issueCoupon */
    public static IdempotencyKey forIssueCoupon(AgentIdentity identity, String userId,
                                                 String couponId, String stableToken) {
        return forAction(identity, "issue_coupon", "issueCoupon",
                userId + "/" + couponId, stableToken);
    }

    /** 创建催单工单 — L2 可逆 */
    public static IdempotencyKey forCreateReminder(AgentIdentity identity, String orderId,
                                                    String stableToken) {
        return forAction(identity, "create_reminder_ticket", "createReminder", orderId, stableToken);
    }

    private static String requireToken(String token) {
        return (token == null || token.isBlank()) ? UUID.randomUUID().toString() : token;
    }

    /** 简单清洗：避免 reason 里冒号污染 token 结构。 */
    private static String sanitize(String reason) {
        if (reason == null) return "none";
        return reason.replace(':', '_').replace('\n', ' ').trim();
    }
}