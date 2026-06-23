package io.github.yysf1949.rag.agent.payment.wechat;

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
 * WeChat Pay mock HTTP server (测试用) — Phase 40 T4.
 *
 * <p>实现最小 WeChat Pay V3 API 表面:
 * <ul>
 *   <li>POST /v3/pay/transactions/jsapi — 创建 JSAPI 订单, 返回 prepay_id</li>
 *   <li>POST /v3/refund/domestic/refunds — 创建退款</li>
 *   <li>POST /v3/test_helper/simulate_webhook — 模拟 webhook 推送</li>
 * </ul>
 *
 * <p>简化点: 真实 V3 用 RSA 签名, 我们用 HMAC-SHA256 (跟 WeChatSignature 规则一致).
 * 真实生产请用 wechatpay-apache-httpclient + 商户私钥.</p>
 */
public class MockWeChatPayServer {

    private final HttpServer server;
    private final int port;
    private final String merchantSecret;
    private final String mchId;
    private final String appId;
    private final ObjectMapper objectMapper;
    private final Map<String, MockOrder> orders = new ConcurrentHashMap<>();

    public MockWeChatPayServer(int port, String mchId, String appId, String merchantSecret) throws IOException {
        this.port = port;
        this.mchId = mchId;
        this.appId = appId;
        this.merchantSecret = merchantSecret;
        this.objectMapper = new ObjectMapper();
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.setExecutor(Executors.newFixedThreadPool(4));
        server.createContext("/v3/pay/transactions/jsapi", new CreateJsapiHandler());
        server.createContext("/v3/refund/domestic/refunds", new CreateRefundHandler());
        server.createContext("/v3/test_helper/simulate_webhook", new SimulateWebhookHandler());
    }

    public void start() { server.start(); }

    public void stop() { server.stop(0); }

    public int getPort() { return port; }
    public String getBaseUrl() { return "http://localhost:" + port; }
    public String getMerchantSecret() { return merchantSecret; }
    public String getMchId() { return mchId; }
    public String getAppId() { return appId; }
    public ObjectMapper getObjectMapper() { return objectMapper; }
    public Map<String, MockOrder> getOrders() { return orders; }

    public static class MockOrder {
        public String outTradeNo;
        public String prepayId;
        public long totalCents;
        public String attach;
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

    private class CreateJsapiHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.sendResponseHeaders(405, -1);
                return;
            }
            Map<String, Object> req = objectMapper.readValue(readBody(exchange), Map.class);
            String outTradeNo = (String) req.get("out_trade_no");
            String prepayId = "wx" + System.currentTimeMillis()
                    + UUID.randomUUID().toString().substring(0, 6);

            MockOrder order = new MockOrder();
            order.outTradeNo = outTradeNo;
            order.prepayId = prepayId;
            order.attach = (String) req.get("attach");
            @SuppressWarnings("unchecked")
            Map<String, Object> amount = (Map<String, Object>) req.get("amount");
            if (amount != null) {
                Object total = amount.get("total");
                if (total instanceof Number) order.totalCents = ((Number) total).longValue();
            }
            orders.put(outTradeNo, order);

            writeJson(exchange, 200, Map.of("prepay_id", prepayId));
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
            String refundId = "rf_" + UUID.randomUUID().toString().substring(0, 16);
            writeJson(exchange, 200, Map.of(
                    "refund_id", refundId,
                    "out_refund_no", req.getOrDefault("out_refund_no", ""),
                    "status", "SUCCESS"
            ));
        }
    }

    /**
     * 模拟 webhook 推送 — 接收业务 URL, 直接 POST webhook payload + 签名.
     */
    private class SimulateWebhookHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            Map<String, Object> req = objectMapper.readValue(readBody(exchange), Map.class);
            String targetUrl = (String) req.get("target_url");
            String outTradeNo = (String) req.get("out_trade_no");

            MockOrder order = orders.get(outTradeNo);
            if (order == null) {
                writeJson(exchange, 404, Map.of("error", "order not found"));
                return;
            }

            String transactionId = "tx_" + UUID.randomUUID().toString().substring(0, 16);
            Map<String, Object> webhookPayload = Map.of(
                    "out_trade_no", order.outTradeNo,
                    "transaction_id", transactionId,
                    "trade_state", "SUCCESS",
                    "amount", Map.of("total", order.totalCents, "currency", "CNY"),
                    "success_time", System.currentTimeMillis() / 1000,
                    "attach", order.attach == null ? "" : order.attach
            );
            String body = objectMapper.writeValueAsString(webhookPayload);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

            long timestamp = System.currentTimeMillis() / 1000;
            String nonceStr = UUID.randomUUID().toString().replace("-", "");
            String signature = WeChatSignature.sign("POST", "/webhook",
                    String.valueOf(timestamp), nonceStr, body, merchantSecret);

            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                    new java.net.URL(targetUrl).openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Wechatpay-Timestamp", String.valueOf(timestamp));
            conn.setRequestProperty("Wechatpay-Nonce", nonceStr);
            conn.setRequestProperty("Wechatpay-Signature", signature);
            conn.setDoOutput(true);
            conn.getOutputStream().write(bodyBytes);
            int code = conn.getResponseCode();

            writeJson(exchange, 200, Map.of(
                    "delivered_status", code,
                    "transaction_id", transactionId
            ));
        }
    }
}
