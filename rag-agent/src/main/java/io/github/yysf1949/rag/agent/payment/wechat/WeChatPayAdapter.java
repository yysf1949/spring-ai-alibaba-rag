package io.github.yysf1949.rag.agent.payment.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.payment.PaymentPort;
import io.github.yysf1949.rag.agent.payment.PaymentPort.CheckoutRequest;
import io.github.yysf1949.rag.agent.payment.PaymentPort.CheckoutResult;
import io.github.yysf1949.rag.agent.payment.PaymentPort.Invoice;
import io.github.yysf1949.rag.agent.payment.PaymentPort.InvoiceStatus;
import io.github.yysf1949.rag.agent.payment.PaymentPort.PaymentMethod;
import io.github.yysf1949.rag.agent.payment.PaymentPort.WebhookEvent;
import io.github.yysf1949.rag.agent.payment.PaymentPort.WebhookOutcome;
import io.github.yysf1949.rag.agent.payment.exception.InvoiceNotFoundException;
import io.github.yysf1949.rag.agent.payment.exception.PaymentValidationException;
import io.github.yysf1949.rag.agent.payment.store.InvoiceStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 微信支付适配器 — Phase 40 T4.
 *
 * <p>实现 {@link PaymentPort}, 用 {@link WeChatPayHttpClient} 调 WeChat Pay V3 API,
 * 通过 {@link InvoiceStore} 持久化 invoice.</p>
 *
 * <h2>幂等性</h2>
 * <p>webhook eventId = {@code out_trade_no + transaction_id}, invoice 状态机
 * PENDING → PAID, 重复返回 DUPLICATE.</p>
 */
@Component
public class WeChatPayAdapter implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(WeChatPayAdapter.class);

    private final WeChatPayHttpClient httpClient;
    private final InvoiceStore invoiceStore;
    private final ObjectMapper objectMapper;

    public WeChatPayAdapter(WeChatPayHttpClient httpClient, InvoiceStore invoiceStore) {
        this(httpClient, invoiceStore, new ObjectMapper());
    }

    public WeChatPayAdapter(WeChatPayHttpClient httpClient, InvoiceStore invoiceStore,
                            ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.invoiceStore = invoiceStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public CheckoutResult createCheckoutSession(CheckoutRequest request) {
        if (request.amountCents() < 1) {
            throw new PaymentValidationException(
                    "WeChat minimum amount is 1 fen, got: " + request.amountCents());
        }
        if (!"CNY".equalsIgnoreCase(request.currency())) {
            throw new PaymentValidationException(
                    "WeChat Pay only supports CNY, got: " + request.currency());
        }

        String invoiceId = Invoice.newInvoiceId();
        String notifyUrl = request.metadata().getOrDefault("notifyUrl",
                "https://api.example.com/api/admin/payment/wechat/webhook");

        try {
            WeChatPayHttpClient.JsapiOrderRequest wechatReq =
                    WeChatPayHttpClient.JsapiOrderRequest.of(
                            httpClient.getAppId(),
                            httpClient.getMchId(),
                            request.description(),
                            invoiceId,
                            notifyUrl,
                            request.amountCents(),
                            request.metadata().getOrDefault("openId", "mock-openid"),
                            null
                    );

            WeChatPayHttpClient.JsapiOrderResponse resp =
                    httpClient.createJsapiOrder(wechatReq);

            // JSAPI 不直接返回 url, 需要前端用 prepay_id 二次签名. 我们的 mock 简化:
            // checkoutUrl = "weixin://wxpay/bizpayurl?pr=<prepay_id>", 模拟 WeChat 收银台
            String checkoutUrl = "weixin://wxpay/bizpayurl?pr=" + resp.prepayId();

            Invoice pending = Invoice.pending(
                    invoiceId, request.tenantId(), request.amountCents(), "CNY",
                    PaymentMethod.WECHAT, resp.prepayId(), request.description());
            invoiceStore.save(pending);

            log.info("WeChat JSAPI order created: invoiceId={} prepayId={} tenantId={}",
                    invoiceId, resp.prepayId(), request.tenantId());

            long expiresAt = Instant.now().toEpochMilli() + 2 * 60 * 60 * 1000L; // 2h
            return new CheckoutResult(invoiceId, checkoutUrl, resp.prepayId(), expiresAt);
        } catch (Exception e) {
            throw new WeChatPayHttpClient.WeChatApiException(
                    "WeChat createJsapiOrder failed: " + e.getMessage());
        }
    }

    @Override
    public WebhookOutcome handleWebhook(WebhookEvent event) {
        if (!"wechat.pay.success".equals(event.type())) {
            log.debug("WeChat webhook ignored: type={}", event.type());
            return WebhookOutcome.IGNORED;
        }
        if (event.tenantId() == null || event.invoiceId() == null) {
            log.warn("WeChat webhook missing tenantId/invoiceId: eventId={}", event.eventId());
            return WebhookOutcome.IGNORED;
        }

        Optional<Invoice> existing = invoiceStore.findById(event.tenantId(), event.invoiceId());
        if (existing.isEmpty()) {
            log.warn("WeChat webhook: invoice not found tenantId={} invoiceId={}",
                    event.tenantId(), event.invoiceId());
            return WebhookOutcome.IGNORED;
        }
        Invoice invoice = existing.get();
        if (invoice.status() == InvoiceStatus.PAID
                || invoice.status() == InvoiceStatus.REFUNDED) {
            log.info("WeChat webhook duplicate: invoiceId={} status={}", invoice.invoiceId(), invoice.status());
            return WebhookOutcome.DUPLICATE;
        }

        Invoice paid = invoice.markPaid(event.paidAt());
        invoiceStore.save(paid);
        log.info("WeChat invoice PAID: invoiceId={} amountCents={}",
                paid.invoiceId(), paid.amountCents());
        return WebhookOutcome.PROCESSED;
    }

    @Override
    public Invoice refund(String tenantId, String invoiceId, String reason) {
        Invoice invoice = invoiceStore.findById(tenantId, invoiceId)
                .orElseThrow(() -> new InvoiceNotFoundException(
                        "Invoice not found: tenantId=" + tenantId + " invoiceId=" + invoiceId));

        if (invoice.status() == InvoiceStatus.REFUNDED) {
            throw new PaymentValidationException(
                    "Invoice already refunded: invoiceId=" + invoiceId);
        }
        if (invoice.status() != InvoiceStatus.PAID) {
            throw new PaymentValidationException(
                    "Can only refund PAID invoice, current status: " + invoice.status());
        }

        try {
            String outRefundNo = "RF-" + invoiceId;
            httpClient.createRefund(new WeChatPayHttpClient.RefundRequest(
                    invoiceId, outRefundNo,
                    reason == null ? "" : reason,
                    new WeChatPayHttpClient.Amount(invoice.amountCents(), "CNY"),
                    null
            ));

            Invoice refunded = invoice.markRefunded(Instant.now().toEpochMilli(),
                    reason == null ? "" : reason);
            invoiceStore.save(refunded);
            log.info("WeChat invoice REFUNDED: invoiceId={} outRefundNo={}", invoiceId, outRefundNo);
            return refunded;
        } catch (Exception e) {
            throw new WeChatPayHttpClient.WeChatApiException(
                    "WeChat refund failed: " + e.getMessage());
        }
    }

    @Override
    public Optional<Invoice> findInvoice(String tenantId, String invoiceId) {
        return invoiceStore.findById(tenantId, invoiceId);
    }

    @Override
    public List<Invoice> listByTenant(String tenantId, int limit) {
        return invoiceStore.listByTenant(tenantId, limit);
    }

    /**
     * 解析 WeChat webhook payload 为 {@link WebhookEvent}.
     */
    public WebhookEvent parseWebhookEvent(String rawBody, String transactionId) throws Exception {
        WeChatWebhookPayload payload = objectMapper.readValue(rawBody, WeChatWebhookPayload.class);
        String outTradeNo = payload.outTradeNo();
        String tenantId = payload.attach();  // 我们用 attach 字段传 tenantId (mock 简化)
        String invoiceId = outTradeNo;       // out_trade_no = invoiceId (我们直接用)

        Optional<Invoice> existing = invoiceStore.findByExternalRef(outTradeNo);
        if (existing.isPresent()) {
            tenantId = existing.get().tenantId();
        }

        return new WebhookEvent(
                outTradeNo + ":" + (transactionId == null ? "" : transactionId),
                "wechat.pay.success",
                tenantId,
                invoiceId,
                outTradeNo,
                payload.amount() == null ? 0L : payload.amount().total(),
                "CNY",
                payload.successTime() == null
                        ? Instant.now().toEpochMilli()
                        : Instant.ofEpochSecond(payload.successTime()).toEpochMilli(),
                rawBody
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record WeChatWebhookPayload(
            @JsonProperty("out_trade_no") String outTradeNo,
            @JsonProperty("transaction_id") String transactionId,
            @JsonProperty("trade_state") String tradeState,
            @JsonProperty("amount") WeChatPayHttpClient.Amount amount,
            @JsonProperty("success_time") Long successTime,
            @JsonProperty("attach") String attach
    ) { }
}
