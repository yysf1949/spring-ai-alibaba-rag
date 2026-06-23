package io.github.yysf1949.rag.agent.payment.exception;

/**
 * 支付参数非法 (跟 {@link io.github.yysf1949.rag.agent.web.FeedbackValidationException}
 * 风格一致 — 独立异常类型避免污染全局 IllegalArgumentException 的 4xx 映射).
 *
 * <p>Phase 40 T4: 单独定义, 后续 PaymentValidationException → 400 Bad Request,
 * 不影响其它 IllegalArgumentException 行为.</p>
 */
public class PaymentValidationException extends RuntimeException {
    public PaymentValidationException(String message) {
        super(message);
    }
}
