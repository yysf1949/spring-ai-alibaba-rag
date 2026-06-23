package io.github.yysf1949.rag.agent.payment;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 支付网关端口 — Phase 40 T4 (R11: 商业化收口第二步).
 *
 * <h2>设计意图</h2>
 * <p>把支付网关抽象为 Port, 上游业务 (Agent / Admin) 通过此 Port 调用, 适配器层
 * 可替换 (Stripe / WeChat Pay / 未来其它). 遵循六边形架构, 跟 {@link
 * io.github.yysf1949.rag.agent.feedback.FeedbackPort} 风格一致.</p>
 *
 * <h2>三个核心操作</h2>
 * <ul>
 *   <li>{@link #createCheckoutSession} — 创建结账会话 (Agent / Admin 调用)</li>
 *   <li>{@link #handleWebhook} — 处理网关回调 (异步, 网关主动 POST)</li>
 *   <li>{@link #refund} — 退款 (Admin 调用)</li>
 * </ul>
 *
 * <h2>webhook 幂等性</h2>
 * <p>支付网关会在网络抖动时重发 webhook. 实现需根据 {@code eventId} (Stripe:
 * {@code event.id}, WeChat Pay: {@code out_trade_no + transaction_id}) 去重.
 * 重复事件返回 {@link WebhookOutcome#DUPLICATE}, 不重复处理 invoice.</p>
 *
 * <h2>字段最小集 (Invoice)</h2>
 * <ul>
 *   <li>{@code invoiceId} — 发票/账单 ID (本地生成, {@code INV-xxxx})</li>
 *   <li>{@code tenantId} — 租户 ID (硬隔离)</li>
 *   <li>{@code amountCents} — 金额 (cents, 避免浮点)</li>
 *   <li>{@code currency} — ISO 4217 (CNY / USD)</li>
 *   <li>{@code status} — PENDING / PAID / REFUNDED / FAILED</li>
 *   <li>{@code paidAt} — 支付成功时间 (epoch millis, 可空)</li>
 *   <li>{@code paymentMethod} — stripe / wechat / api</li>
 *   <li>{@code externalRef} — 网关侧引用 (Stripe session id / WeChat transaction_id)</li>
 * </ul>
 */
public interface PaymentPort {

    /**
     * 创建结账会话 — Agent / Admin 调用, 拿到 URL 让用户跳转付款.
     *
     * @param request 结账请求 (tenantId / amountCents / currency / metadata)
     * @return 创建结果 (含 checkoutUrl + invoiceId + externalRef)
     */
    CheckoutResult createCheckoutSession(CheckoutRequest request);

    /**
     * 处理网关 webhook — 异步回调入口, 实现需保证幂等.
     *
     * <p>典型调用路径: 网关 → 我们的 WebhookController → 此方法 →
     * 解析事件 → 更新 Invoice 状态 → 返回 DUPLICATE (已处理) 或
     * PROCESSED (新处理).</p>
     *
     * @param event 解析后的 webhook 事件 (含 eventId / type / payload)
     * @return 处理结果
     */
    WebhookOutcome handleWebhook(WebhookEvent event);

    /**
     * 退款 — Admin 调用, 状态机: PAID → REFUNDED.
     *
     * @param tenantId 租户 ID (硬隔离)
     * @param invoiceId 发票 ID
     * @param reason 退款原因
     * @return 退款后的 invoice
     * @throws io.github.yysf1949.rag.agent.payment.exception.InvoiceNotFoundException
     *         invoice 不存在或跨租户
     * @throws io.github.yysf1949.rag.agent.payment.exception.PaymentValidationException
     *         invoice 非 PAID 状态
     */
    Invoice refund(String tenantId, String invoiceId, String reason);

    /**
     * 按 ID 查询 invoice.
     */
    Optional<Invoice> findInvoice(String tenantId, String invoiceId);

    /**
     * 列某租户的 invoice — 分页.
     */
    List<Invoice> listByTenant(String tenantId, int limit);

    /**
     * 结账请求.
     *
     * @param tenantId    租户 ID (必填)
     * @param amountCents 金额 (cents, 必填 ≥ 1)
     * @param currency    货币 (CNY / USD, 默认 USD)
     * @param description 商品/服务描述 (给收银台显示)
     * @param metadata    自定义元数据 (写到 gateway session, webhook 回传)
     * @param successUrl  付款成功跳转 URL (Stripe redirect flow 用)
     * @param cancelUrl   付款取消跳转 URL
     */
    record CheckoutRequest(
            String tenantId,
            long amountCents,
            String currency,
            String description,
            Map<String, String> metadata,
            String successUrl,
            String cancelUrl
    ) {
        public CheckoutRequest {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId required");
            }
            if (amountCents < 1) {
                throw new IllegalArgumentException("amountCents must be >= 1, got: " + amountCents);
            }
            if (currency == null || currency.isBlank()) {
                currency = "USD";
            }
            if (description == null) {
                description = "RAG Agent Service";
            }
            if (metadata == null) {
                metadata = Map.of();
            }
        }
    }

    /**
     * 结账结果.
     *
     * @param invoiceId    本地 invoice ID (INV-xxxx)
     * @param checkoutUrl  用户跳转的付款 URL (Stripe hosted checkout / WeChat QR code)
     * @param externalRef  网关侧引用 (Stripe session id / WeChat prepay_id)
     * @param expiresAt    URL 过期时间 (epoch millis, Stripe 通常 24h, WeChat 2h)
     */
    record CheckoutResult(
            String invoiceId,
            String checkoutUrl,
            String externalRef,
            long expiresAt
    ) { }

    /**
     * webhook 事件 (统一抽象).
     *
     * @param eventId     事件唯一 ID (Stripe: event.id, WeChat: out_trade_no+transaction_id 拼)
     * @param type        事件类型 (checkout.session.completed / wechat.pay.success / ...)
     * @param tenantId    关联租户 ID (从 session metadata 反查)
     * @param invoiceId   关联 invoice ID (从 session metadata 反查)
     * @param externalRef 网关侧引用
     * @param amountCents 实际支付金额 (校验用)
     * @param currency    实际支付货币
     * @param paidAt      支付完成时间 (epoch millis)
     * @param rawPayload  原始 payload (JSON 字符串, 给审计/调试用)
     */
    record WebhookEvent(
            String eventId,
            String type,
            String tenantId,
            String invoiceId,
            String externalRef,
            long amountCents,
            String currency,
            long paidAt,
            String rawPayload
    ) { }

    /**
     * webhook 处理结果.
     */
    enum WebhookOutcome {
        /** 新事件, invoice 已更新 (PENDING → PAID) */
        PROCESSED,
        /** 重复事件, 已处理过, 跳过 */
        DUPLICATE,
        /** 事件类型不识别, 跳过 */
        IGNORED
    }

    /**
     * Invoice 状态.
     */
    enum InvoiceStatus {
        PENDING, PAID, REFUNDED, FAILED
    }

    /**
     * 支付方式.
     */
    enum PaymentMethod {
        STRIPE, WECHAT, API;

        public static PaymentMethod of(String s) {
            if (s == null) return API;
            return PaymentMethod.valueOf(s.toUpperCase());
        }
    }

    /**
     * Invoice 记录.
     */
    record Invoice(
            String invoiceId,
            String tenantId,
            long amountCents,
            String currency,
            InvoiceStatus status,
            Long paidAt,
            PaymentMethod paymentMethod,
            String externalRef,
            String description,
            long createdAt,
            Long refundedAt,
            String refundReason
    ) {
        public Invoice {
            if (invoiceId == null || invoiceId.isBlank()) {
                throw new IllegalArgumentException("invoiceId required");
            }
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId required");
            }
            if (amountCents < 0) {
                throw new IllegalArgumentException("amountCents must be >= 0, got: " + amountCents);
            }
            if (currency == null || currency.isBlank()) {
                throw new IllegalArgumentException("currency required");
            }
            if (status == null) {
                throw new IllegalArgumentException("status required");
            }
            if (paymentMethod == null) {
                throw new IllegalArgumentException("paymentMethod required");
            }
        }

        public static String newInvoiceId() {
            return "INV-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }

        /**
         * 创建初始 PENDING invoice (结账会话时用).
         */
        public static Invoice pending(
                String invoiceId, String tenantId, long amountCents, String currency,
                PaymentMethod method, String externalRef, String description
        ) {
            return new Invoice(
                    invoiceId, tenantId, amountCents, currency,
                    InvoiceStatus.PENDING, null, method, externalRef, description,
                    Instant.now().toEpochMilli(), null, null
            );
        }

        /**
         * 标记已支付 (webhook 处理时用).
         */
        public Invoice markPaid(long paidAt) {
            return new Invoice(invoiceId, tenantId, amountCents, currency,
                    InvoiceStatus.PAID, paidAt, paymentMethod, externalRef, description,
                    createdAt, refundedAt, refundReason);
        }

        /**
         * 标记已退款 (refund 调用时用).
         */
        public Invoice markRefunded(long refundedAt, String reason) {
            return new Invoice(invoiceId, tenantId, amountCents, currency,
                    InvoiceStatus.REFUNDED, paidAt, paymentMethod, externalRef, description,
                    createdAt, refundedAt, reason);
        }
    }
}