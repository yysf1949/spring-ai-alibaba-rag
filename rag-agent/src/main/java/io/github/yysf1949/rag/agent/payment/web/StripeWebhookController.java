package io.github.yysf1949.rag.agent.payment.web;

import io.github.yysf1949.rag.agent.payment.PaymentPort;
import io.github.yysf1949.rag.agent.payment.PaymentPort.WebhookEvent;
import io.github.yysf1949.rag.agent.payment.PaymentPort.WebhookOutcome;
import io.github.yysf1949.rag.agent.payment.exception.PaymentValidationException;
import io.github.yysf1949.rag.agent.payment.stripe.StripePaymentAdapter;
import io.github.yysf1949.rag.agent.payment.stripe.StripeSignatureVerifier;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Stripe webhook 端点 — Phase 40 T4.
 *
 * <p>POST /api/admin/payment/stripe/webhook, 接收 Stripe webhook 回调.
 * 流程: 验证签名 → 解析 payload → 调 PaymentPort.handleWebhook → 返回结果.</p>
 *
 * <p>本 controller 走 admin 路径, 不需要租户 header — webhook 是 Stripe → 我们
 * 的服务端点, tenantId 从 webhook metadata 反查.</p>
 */
@RestController
@RequestMapping("/api/admin/payment/stripe")
@Tag(name = "StripeWebhook", description = "Phase 40 T4: Stripe webhook 端点 (R11).")
public class StripeWebhookController {

    private static final Logger log = LoggerFactory.getLogger(StripeWebhookController.class);

    private final StripeSignatureVerifier signatureVerifier;
    private final StripePaymentAdapter stripeAdapter;
    private final boolean signatureRequired;

    public StripeWebhookController(
            StripeSignatureVerifier signatureVerifier,
            StripePaymentAdapter stripeAdapter,
            @Value("${rag.payment.stripe.webhook.signature-required:true}") boolean signatureRequired) {
        this.signatureVerifier = signatureVerifier;
        this.stripeAdapter = stripeAdapter;
        this.signatureRequired = signatureRequired;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Stripe webhook 回调 (签名验证 + 幂等处理)")
    public ResponseEntity<Map<String, Object>> handleStripeWebhook(
            @RequestHeader(value = "Stripe-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (signatureRequired && (signature == null || signature.isBlank())) {
            throw new PaymentValidationException("Stripe-Signature header required");
        }
        if (signatureRequired && !signatureVerifier.verify(signature, rawBody)) {
            throw new PaymentValidationException("Stripe signature verification failed");
        }

        try {
            WebhookEvent event = stripeAdapter.parseWebhookEvent(rawBody);
            WebhookOutcome outcome = stripeAdapter.handleWebhook(event);
            log.info("Stripe webhook processed: eventId={} type={} outcome={}",
                    event.eventId(), event.type(), outcome);
            return ResponseEntity.ok(Map.of(
                    "received", true,
                    "outcome", outcome.name(),
                    "eventId", event.eventId()
            ));
        } catch (PaymentValidationException pve) {
            throw pve;
        } catch (Exception e) {
            log.error("Stripe webhook parse failed: {}", e.getMessage(), e);
            throw new PaymentValidationException("Stripe webhook parse failed: " + e.getMessage());
        }
    }
}
