package io.github.yysf1949.rag.agent.payment.stripe;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;

/**
 * Stripe mock HTTP server (测试用) — Phase 40 T4.
 *
 * <p>实现最小 Stripe API 表面:
 * <ul>
 *   <li>POST /v1/checkout/sessions — 创建 checkout session, 返回假 session id + url</li>
 *   <li>POST /v1/refunds — 创建 refund</li>
 *   <li>POST /v1/test_helper/simulate_webhook — 模拟 webhook 推送 (测试用)</li>
 * </ul>
 *
 * <p>跟真 Stripe 不同: 这里允许 test_helper 端点主动 push webhook 给我们的
 * 业务 webhook endpoint, 简化 E2E 测试.</p>
 */
public class MockStripeServer {

    private final HttpServer server;
    private final int port;
    private final String webhookSecret;
    private final ObjectMapper objectMapper;
    private final Map<String, MockSession> sessions = new ConcurrentHashMap<>();

    public MockStripeServer(int port, String webhookSecret) throws IOException {
        this.port = port;
        this.webhookSecret = webhookSecret;
        this.objectMapper = new ObjectMapper();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v1/checkout/sessions", new CreateSessionHandler());
        server.createContext("/v1/refunds", new CreateRefundHandler());
        server.createContext("/v1/test_helper/simulate_webhook", new SimulateWebhookHandler());
    }

    public void start() {
        server.start();
    }

    public void stop() {
        server.stop(0);
    }

    public int getPort() { return port; }

    public String getWebhookSecret() { return webhookSecret; }

    public String getBaseUrl() {
        return "http://localhost:" + port;
    }

    public ObjectMapper getObjectMapper() { return objectMapper; }

    public Map<String, MockSession> getSessions() { return sessions; }

    /** mock session 状态. */
    public static class MockSession {
        public String id;
        public String tenantId;
        public String invoiceId;
        public long amountTotal;
        public String currency;
    }

    private void writeJson(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = objectMapper.writeValueAsBytes(body);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private byte[] readBody(HttpExchange exchange) throws IOException {
        return exchange.getRequestBody().readAllBytes();
    }

    private class CreateSessionHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> req = objectMapper.readValue(readBody(exchange), Map.class);
            @SuppressWarnings("unchecked")
            java.util.List<Map<String, Object>> lineItems =
                    (java.util.List<Map<String, Object>>) req.get("line_items");
            @SuppressWarnings("unchecked")
            Map<String, String> metadata = (Map<String, String>) req.get("metadata");

            long amountTotal = 0;
            String currency = "usd";
            if (lineItems != null && !lineItems.isEmpty()) {
                @SuppressWarnings("unchecked")
                Map<String, Object> priceData =
                        (Map<String, Object>) lineItems.get(0).get("price_data");
                if (priceData != null) {
                    Object amount = priceData.get("unit_amount");
                    if (amount instanceof Number) amountTotal = ((Number) amount).longValue();
                    Object cur = priceData.get("currency");
                    if (cur != null) currency = cur.toString();
                }
            }

            String sessionId = "cs_test_" + UUID.randomUUID().toString().substring(0, 16);
            MockSession mock = new MockSession();
            mock.id = sessionId;
            mock.tenantId = metadata == null ? null : metadata.get("tenantId");
            mock.invoiceId = metadata == null ? null : metadata.get("invoiceId");
            mock.amountTotal = amountTotal;
            mock.currency = currency;
            sessions.put(sessionId, mock);

            long expiresAt = System.currentTimeMillis() / 1000 + 24 * 3600;
            Map<String, Object> resp = Map.of(
                    "id", sessionId,
                    "object", "checkout.session",
                    "url", "https://checkout.stripe.com/c/pay/" + sessionId,
                    "expires_at", expiresAt,
                    "payment_status", "unpaid"
            );
            writeJson(exchange, 200, resp);
        }
    }

    private class CreateRefundHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> req = objectMapper.readValue(readBody(exchange), Map.class);
            String refundId = "re_test_" + UUID.randomUUID().toString().substring(0, 16);
            Map<String, Object> resp = Map.of(
                    "id", refundId,
                    "object", "refund",
                    "status", "succeeded",
                    "amount", req.getOrDefault("amount", 0)
            );
            writeJson(exchange, 200, resp);
        }
    }

    /**
     * 模拟 webhook 推送 — 接收业务 URL, 直接 POST webhook payload + 签名.
     *
     * <p>调用方式: POST /v1/test_helper/simulate_webhook with body
     * {@code { "target_url": "...", "type": "checkout.session.completed", "session_id": "..." }}</p>
     */
    private class SimulateWebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> req = objectMapper.readValue(readBody(exchange), Map.class);
            String targetUrl = (String) req.get("target_url");
            String type = (String) req.get("type");
            String sessionId = (String) req.get("session_id");

            MockSession mock = sessions.get(sessionId);
            if (mock == null) {
                writeJson(exchange, 404, Map.of("error", "session not found"));
                return;
            }

            Map<String, Object> webhookPayload = Map.of(
                    "id", "evt_" + UUID.randomUUID().toString().substring(0, 16),
                    "type", type,
                    "created", System.currentTimeMillis() / 1000,
                    "data", Map.of("object", Map.of(
                            "id", mock.id,
                            "amount_total", mock.amountTotal,
                            "currency", mock.currency,
                            "payment_status", "paid",
                            "metadata", Map.of(
                                    "tenantId", mock.tenantId == null ? "" : mock.tenantId,
                                    "invoiceId", mock.invoiceId == null ? "" : mock.invoiceId
                            )
                    ))
            );
            String body = objectMapper.writeValueAsString(webhookPayload);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            long timestamp = System.currentTimeMillis() / 1000;
            String signedPayload = timestamp + "." + body;
            String signature = StripeSignatureVerifier.hmacSha256Hex(webhookSecret, signedPayload);

            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(targetUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Stripe-Signature", "t=" + timestamp + ",v1=" + signature);
            conn.setDoOutput(true);
            conn.getOutputStream().write(bodyBytes);
            int code = conn.getResponseCode();

            writeJson(exchange, 200, Map.of(
                    "delivered_status", code,
                    "event_id", webhookPayload.get("id")
            ));
        }
    }
}
