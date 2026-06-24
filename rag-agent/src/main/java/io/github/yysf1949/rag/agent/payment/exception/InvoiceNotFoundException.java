package io.github.yysf1949.rag.agent.payment.exception;

/**
 * Invoice 不存在或跨租户访问 — 404 Not Found.
 *
 * <p>Phase 40 T4: 支付场景的 "找不到" 异常, 走 404 而非 400, 因为是资源语义
 * 而非参数校验.</p>
 */
public class InvoiceNotFoundException extends RuntimeException {
    public InvoiceNotFoundException(String message) {
        super(message);
    }
}
