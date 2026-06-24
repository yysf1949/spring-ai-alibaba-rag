package io.github.yysf1949.rag.agent.payment.web;

import io.github.yysf1949.rag.agent.payment.PaymentPort.WebhookEvent;
import io.github.yysf1949.rag.agent.payment.PaymentPort.WebhookOutcome;
import io.github.yysf1949.rag.agent.payment.exception.PaymentValidationException;
import io.github.yysf1949.rag.agent.payment.wechat.WeChatPayAdapter;
import io.github.yysf1949.rag.agent.payment.wechat.WeChatSignature;
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
 * WeChat Pay webhook 端点 — Phase 40 T4.
 *
 * <p>POST /api/admin/payment/wechat/webhook, 接收微信支付回调.
 * 流程: 验证签名 (Wechatpay-Signature) → 解析 payload → 调 PaymentPort.handleWebhook.</p>
 */
@RestController
@RequestMapping("/api/admin/payment/wechat")
@Tag(name = "WeChatWebhook", description = "Phase 40 T4: WeChat Pay webhook 端点 (R11).")
public class WeChatWebhookController {

    private static final Logger log = LoggerFactory.getLogger(WeChatWebhookController.class);

    private final WeChatPayAdapter wechatAdapter;
    private final String merchantSecret;
    private final boolean signatureRequired;

    public WeChatWebhookController(
            WeChatPayAdapter wechatAdapter,
            @Value("${rag.payment.wechat.merchant-secret:test-merchant-secret}") String merchantSecret,
            @Value("${rag.payment.wechat.webhook.signature-required:true}") boolean signatureRequired) {
        this.wechatAdapter = wechatAdapter;
        this.merchantSecret = merchantSecret;
        this.signatureRequired = signatureRequired;
    }

    @PostMapping(value = "/webhook", consumes = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "WeChat webhook 回调 (签名验证 + 幂等处理)")
    public ResponseEntity<Map<String, Object>> handleWeChatWebhook(
            @RequestHeader(value = "Wechatpay-Timestamp", required = false) String timestamp,
            @RequestHeader(value = "Wechatpay-Nonce", required = false) String nonce,
            @RequestHeader(value = "Wechatpay-Signature", required = false) String signature,
            @RequestBody String rawBody) {
        if (signatureRequired) {
            if (timestamp == null || nonce == null || signature == null) {
                throw new PaymentValidationException(
                        "Wechatpay-Timestamp / Nonce / Signature headers required");
            }
            if (!WeChatSignature.verifyWebhook(timestamp, nonce, rawBody, signature, merchantSecret)) {
                throw new PaymentValidationException("WeChat signature verification failed");
            }
        }

        try {
            // transactionId 暂不需要, 但 parseWebhookEvent 接受 null
            WebhookEvent event = wechatAdapter.parseWebhookEvent(rawBody, null);
            WebhookOutcome outcome = wechatAdapter.handleWebhook(event);
            log.info("WeChat webhook processed: invoiceId={} outcome={}",
                    event.invoiceId(), outcome);
            return ResponseEntity.ok(Map.of(
                    "received", true,
                    "outcome", outcome.name(),
                    "invoiceId", event.invoiceId() == null ? "" : event.invoiceId()
            ));
        } catch (PaymentValidationException pve) {
            throw pve;
        } catch (Exception e) {
            log.error("WeChat webhook parse failed: {}", e.getMessage(), e);
            throw new PaymentValidationException("WeChat webhook parse failed: " + e.getMessage());
        }
    }
}
