package io.github.yysf1949.rag.agent.payment.wechat;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * 微信支付 V3 HTTP 客户端 (轻量自研) — Phase 40 T4.
 *
 * <h2>真实 WeChat Pay V3 JSAPI</h2>
 * <pre>
 * POST https://api.mch.weixin.qq.com/v3/pay/transactions/jsapi
 * Authorization: WECHATPAY2-SHA256-RSA2048 mchid="123",nonce_str="...",timestamp="...",signature="..."
 * Content-Type: application/json
 *
 * { "appid": "...", "mchid": "...", "description": "...", "out_trade_no": "INV-xxx",
 *   "notify_url": "...", "amount": { "total": 100, "currency": "CNY" },
 *   "payer": { "openid": "..." } }
 * </pre>
 *
 * <p>我们的简化版: 用 HMAC-SHA256(merchantSecret) 代替真实生产用的 RSA 签名,
 * webhook 校验同样用 HMAC-SHA256. 这是 mock-only 适配, 真生产请用 wechatpay-apache-httpclient.</p>
 */
public class WeChatPayHttpClient {

    private final String apiBase;
    private final String mchId;
    private final String appId;
    private final String merchantSecret;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public WeChatPayHttpClient(String apiBase, String mchId, String appId, String merchantSecret) {
        this(apiBase, mchId, appId, merchantSecret, new ObjectMapper(), Duration.ofSeconds(10));
    }

    public WeChatPayHttpClient(String apiBase, String mchId, String appId, String merchantSecret,
                               ObjectMapper objectMapper, Duration timeout) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.mchId = mchId;
        this.appId = appId;
        this.merchantSecret = merchantSecret;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public JsapiOrderResponse createJsapiOrder(JsapiOrderRequest req) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(req);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String signature = WeChatSignature.sign("POST", "/v3/pay/transactions/jsapi",
                timestamp, nonceStr, jsonBody, merchantSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/v3/pay/transactions/jsapi"))
                .timeout(timeout)
                .header("Authorization",
                        String.format("WECHATPAY2-SHA256-HMAC256 mchid=\"%s\",nonce_str=\"%s\","
                                + "timestamp=\"%s\",signature=\"%s\"", mchId, nonceStr, timestamp, signature))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new WeChatApiException(
                    "createJsapiOrder failed: status=" + response.statusCode()
                            + " body=" + response.body());
        }
        return objectMapper.readValue(response.body(), JsapiOrderResponse.class);
    }

    public RefundResponse createRefund(RefundRequest req) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(req);
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String nonceStr = UUID.randomUUID().toString().replace("-", "");
        String signature = WeChatSignature.sign("POST", "/v3/refund/domestic/refunds",
                timestamp, nonceStr, jsonBody, merchantSecret);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/v3/refund/domestic/refunds"))
                .timeout(timeout)
                .header("Authorization",
                        String.format("WECHATPAY2-SHA256-HMAC256 mchid=\"%s\",nonce_str=\"%s\","
                                + "timestamp=\"%s\",signature=\"%s\"", mchId, nonceStr, timestamp, signature))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new WeChatApiException(
                    "createRefund failed: status=" + response.statusCode()
                            + " body=" + response.body());
        }
        return objectMapper.readValue(response.body(), RefundResponse.class);
    }

    public String getApiBase() { return apiBase; }
    public String getMchId() { return mchId; }
    public String getMerchantSecret() { return merchantSecret; }

    public String getAppId() { return appId; }


    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsapiOrderRequest(
            @JsonProperty("appid") String appId,
            @JsonProperty("mchid") String mchId,
            @JsonProperty("description") String description,
            @JsonProperty("out_trade_no") String outTradeNo,
            @JsonProperty("notify_url") String notifyUrl,
            @JsonProperty("amount") Amount amount,
            @JsonProperty("payer") Payer payer,
            @JsonProperty("attach") String attach
    ) {
        public static JsapiOrderRequest of(String appId, String mchId, String description,
                                           String outTradeNo, String notifyUrl,
                                           long totalCents, String openId, String attach) {
            return new JsapiOrderRequest(appId, mchId, description, outTradeNo, notifyUrl,
                    new Amount(totalCents, "CNY"),
                    new Payer(openId),
                    attach);
        }


    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(
            @JsonProperty("total") long total,
            @JsonProperty("currency") String currency
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Payer(@JsonProperty("openid") String openId) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record JsapiOrderResponse(
            @JsonProperty("prepay_id") String prepayId
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundRequest(
            @JsonProperty("out_trade_no") String outTradeNo,
            @JsonProperty("out_refund_no") String outRefundNo,
            @JsonProperty("reason") String reason,
            @JsonProperty("amount") Amount amount,
            @JsonProperty("notify_url") String notifyUrl
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResponse(
            @JsonProperty("refund_id") String refundId,
            @JsonProperty("out_refund_no") String outRefundNo,
            @JsonProperty("status") String status
    ) { }

    public static class WeChatApiException extends RuntimeException {
        public WeChatApiException(String message) {
            super(message);
        }
    }
}
