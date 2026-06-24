package io.github.yysf1949.rag.agent.payment.stripe;

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
 * Stripe 支付适配器 — Phase 40 T4.
 *
 * <p>实现 {@link PaymentPort}, 用 {@link StripeHttpClient} 调 Stripe API,
 * 通过 {@link InvoiceStore} 持久化 invoice.</p>
 *
 * <h2>幂等性</h2>
 * <p>webhook 用 {@code event.id} 去重 — 通过判断 invoice 当前状态实现:
 * PAID/REFUNDED → DUPLICATE, PENDING → PROCESSED.</p>
 */
@Component
@org.springframework.context.annotation.Primary
public class StripePaymentAdapter implements PaymentPort {

    private static final Logger log = LoggerFactory.getLogger(StripePaymentAdapter.class);

    private final StripeHttpClient httpClient;
    private final InvoiceStore invoiceStore;
    private final ObjectMapper objectMapper;

    public StripePaymentAdapter(StripeHttpClient httpClient, InvoiceStore invoiceStore) {
        this(httpClient, invoiceStore, new ObjectMapper());
    }

    public StripePaymentAdapter(StripeHttpClient httpClient, InvoiceStore invoiceStore,
                                ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.invoiceStore = invoiceStore;
        this.objectMapper = objectMapper;
    }

    @Override
    public CheckoutResult createCheckoutSession(CheckoutRequest request) {
        if (request.amountCents() < 50) {
            throw new PaymentValidationException(
                    "Stripe minimum amount is 50 cents, got: " + request.amountCents());
        }

        String invoiceId = Invoice.newInvoiceId();
        Map<String, String> metadata = new HashMap<>(request.metadata());
        metadata.put("tenantId", request.tenantId());
        metadata.put("invoiceId", invoiceId);

        try {
            StripeHttpClient.CheckoutSessionRequest stripeReq =
                    StripeHttpClient.CheckoutSessionRequest.payment(
                            request.amountCents(),
                            request.currency().toLowerCase(),
                            request.description(),
                            request.successUrl(),
                            request.cancelUrl(),
                            metadata
                    );

            StripeHttpClient.CheckoutSessionResponse resp =
                    httpClient.createCheckoutSession(stripeReq);

            Invoice pending = Invoice.pending(
                    invoiceId, request.tenantId(), request.amountCents(),
                    request.currency().toUpperCase(), PaymentMethod.STRIPE,
                    resp.id(), request.description());
            invoiceStore.save(pending);

            log.info("Stripe checkout session created: invoiceId={} sessionId={} tenantId={}",
                    invoiceId, resp.id(), request.tenantId());

            return new CheckoutResult(
                    invoiceId, resp.url(), resp.id(), resp.expiresAtMillis());
        } catch (Exception e) {
            throw new StripeHttpClient.StripeApiException(
                    "Stripe createCheckoutSession failed: " + e.getMessage());
        }
    }

    @Override
    public WebhookOutcome handleWebhook(WebhookEvent event) {
        if (!"checkout.session.completed".equals(event.type())) {
            log.debug("Stripe webhook ignored: type={}", event.type());
            return WebhookOutcome.IGNORED;
        }
        if (event.tenantId() == null || event.invoiceId() == null) {
            log.warn("Stripe webhook missing tenantId/invoiceId: eventId={}", event.eventId());
            return WebhookOutcome.IGNORED;
        }

        Optional<Invoice> existing = invoiceStore.findById(event.tenantId(), event.invoiceId());
        if (existing.isEmpty()) {
            log.warn("Stripe webhook: invoice not found tenantId={} invoiceId={}",
                    event.tenantId(), event.invoiceId());
            return WebhookOutcome.IGNORED;
        }
        Invoice invoice = existing.get();
        if (invoice.status() == InvoiceStatus.PAID
                || invoice.status() == InvoiceStatus.REFUNDED) {
            log.info("Stripe webhook duplicate: invoiceId={} status={}", invoice.invoiceId(), invoice.status());
            return WebhookOutcome.DUPLICATE;
        }

        if (event.amountCents() != invoice.amountCents()) {
            log.warn("Stripe webhook amount mismatch: invoice={} webhook={}",
                    invoice.amountCents(), event.amountCents());
        }

        Invoice paid = invoice.markPaid(event.paidAt());
        invoiceStore.save(paid);
        log.info("Stripe invoice PAID: invoiceId={} amountCents={} currency={}",
                paid.invoiceId(), paid.amountCents(), paid.currency());
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
            httpClient.createRefund(new StripeHttpClient.RefundRequest(
                    invoice.externalRef(), reason));

            Invoice refunded = invoice.markRefunded(Instant.now().toEpochMilli(),
                    reason == null ? "" : reason);
            invoiceStore.save(refunded);
            log.info("Stripe invoice REFUNDED: invoiceId={} reason={}", invoiceId, reason);
            return refunded;
        } catch (Exception e) {
            throw new StripeHttpClient.StripeApiException(
                    "Stripe refund failed: " + e.getMessage());
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
     * 解析 Stripe webhook payload 为 {@link WebhookEvent}.
     *
     * <p>调用方 (controller) 在 verify signature 之后调用此方法.</p>
     */
    public WebhookEvent parseWebhookEvent(String rawBody) throws Exception {
        StripeWebhookPayload payload = objectMapper.readValue(rawBody, StripeWebhookPayload.class);
        StripeWebhookPayload.EventObject session = payload.data().object();

        Map<String, String> metadata = session.metadata() == null ? Map.of() : session.metadata();
        String tenantId = metadata.get("tenantId");
        String invoiceId = metadata.get("invoiceId");

        return new WebhookEvent(
                payload.id(),
                payload.type(),
                tenantId,
                invoiceId,
                session.id(),
                session.amountTotal() == null ? 0L : session.amountTotal(),
                session.currency() == null ? "USD" : session.currency().toUpperCase(),
                payload.created() == null ? Instant.now().toEpochMilli() : payload.created() * 1000L,
                rawBody
        );
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StripeWebhookPayload(
            @JsonProperty("id") String id,
            @JsonProperty("type") String type,
            @JsonProperty("created") Long created,
            @JsonProperty("data") EventData data
    ) {
        @JsonIgnoreProperties(ignoreUnknown = true)
        public record EventData(
                @JsonProperty("object") EventObject object
        ) { }

        @JsonIgnoreProperties(ignoreUnknown = true)
        public record EventObject(
                @JsonProperty("id") String id,
                @JsonProperty("amount_total") Long amountTotal,
                @JsonProperty("currency") String currency,
                @JsonProperty("payment_status") String paymentStatus,
                @JsonProperty("metadata") Map<String, String> metadata
        ) { }
    }
}
