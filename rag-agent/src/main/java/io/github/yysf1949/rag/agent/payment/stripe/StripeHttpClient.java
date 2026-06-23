package io.github.yysf1949.rag.agent.payment.stripe;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

/**
 * Stripe API HTTP 客户端 (轻量自研) — Phase 40 T4.
 *
 * <h2>为什么不直接用 stripe-java SDK</h2>
 * <ul>
 *   <li>SDK 依赖较多 (gson / okhttp), 跟项目轻依赖风格不符</li>
 *   <li>本任务范围是 mock E2E, 只用 checkout.sessions.create + verify webhook 两个端点</li>
 *   <li>自研 ~100 行代码足够, 测试可控 (用 {@link MockStripeServer} 模拟)</li>
 * </ul>
 */
public class StripeHttpClient {

    private final String apiBase;
    private final String secretKey;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final Duration timeout;

    public StripeHttpClient(String apiBase, String secretKey) {
        this(apiBase, secretKey, new ObjectMapper(), Duration.ofSeconds(10));
    }

    public StripeHttpClient(String apiBase, String secretKey,
                            ObjectMapper objectMapper, Duration timeout) {
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
        this.secretKey = secretKey;
        this.objectMapper = objectMapper;
        this.timeout = timeout;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(timeout)
                .build();
    }

    public CheckoutSessionResponse createCheckoutSession(CheckoutSessionRequest req) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(req);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/v1/checkout/sessions"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new StripeApiException(
                    "createCheckoutSession failed: status=" + response.statusCode()
                            + " body=" + response.body());
        }
        return objectMapper.readValue(response.body(), CheckoutSessionResponse.class);
    }

    public RefundResponse createRefund(RefundRequest req) throws Exception {
        String jsonBody = objectMapper.writeValueAsString(req);
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(apiBase + "/v1/refunds"))
                .timeout(timeout)
                .header("Authorization", "Bearer " + secretKey)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() / 100 != 2) {
            throw new StripeApiException(
                    "createRefund failed: status=" + response.statusCode()
                            + " body=" + response.body());
        }
        return objectMapper.readValue(response.body(), RefundResponse.class);
    }

    public String getApiBase() { return apiBase; }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckoutSessionRequest(
            @JsonProperty("mode") String mode,
            @JsonProperty("success_url") String successUrl,
            @JsonProperty("cancel_url") String cancelUrl,
            @JsonProperty("line_items") java.util.List<LineItem> lineItems,
            @JsonProperty("metadata") java.util.Map<String, String> metadata,
            @JsonProperty("payment_intent_data") PaymentIntentData paymentIntentData
    ) {
        public static CheckoutSessionRequest payment(
                long amountCents, String currency, String description,
                String successUrl, String cancelUrl,
                java.util.Map<String, String> metadata
        ) {
            return new CheckoutSessionRequest(
                    "payment",
                    successUrl,
                    cancelUrl,
                    java.util.List.of(new LineItem(
                            1L,
                            new PriceData(currency, amountCents, description)
                    )),
                    metadata,
                    null
            );
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LineItem(
            @JsonProperty("quantity") long quantity,
            @JsonProperty("price_data") PriceData priceData
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PriceData(
            @JsonProperty("currency") String currency,
            @JsonProperty("unit_amount") long unitAmount,
            @JsonProperty("product_data") ProductData productData
    ) {
        public PriceData(String currency, long unitAmount, String name) {
            this(currency, unitAmount, new ProductData(name));
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ProductData(@JsonProperty("name") String name) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PaymentIntentData(@JsonProperty("metadata") java.util.Map<String, String> metadata) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CheckoutSessionResponse(
            @JsonProperty("id") String id,
            @JsonProperty("object") String object,
            @JsonProperty("url") String url,
            @JsonProperty("expires_at") Long expiresAt,
            @JsonProperty("payment_status") String paymentStatus
    ) {
        public long expiresAtMillis() {
            return expiresAt == null ? Instant.now().toEpochMilli() + Duration.ofHours(24).toMillis()
                    : expiresAt * 1000L;
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundRequest(
            @JsonProperty("payment_intent") String paymentIntent,
            @JsonProperty("reason") String reason
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RefundResponse(
            @JsonProperty("id") String id,
            @JsonProperty("object") String object,
            @JsonProperty("status") String status,
            @JsonProperty("amount") Long amount
    ) { }

    public static class StripeApiException extends RuntimeException {
        public StripeApiException(String message) {
            super(message);
        }
    }
}
