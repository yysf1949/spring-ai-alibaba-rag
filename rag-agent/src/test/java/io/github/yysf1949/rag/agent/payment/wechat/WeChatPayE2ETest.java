package io.github.yysf1949.rag.agent.payment.wechat;

import io.github.yysf1949.rag.agent.payment.PaymentPort;
import io.github.yysf1949.rag.agent.payment.PaymentPort.CheckoutRequest;
import io.github.yysf1949.rag.agent.payment.PaymentPort.CheckoutResult;
import io.github.yysf1949.rag.agent.payment.PaymentPort.InvoiceStatus;
import io.github.yysf1949.rag.agent.payment.store.InMemoryInvoiceStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * WeChat Pay E2E 真实测试 — MockWeChatPayServer → WeChatPayAdapter → Webhook → Invoice.
 *
 * <p>跑 mock HTTP 服务器, 真调用 {@link WeChatPayAdapter#createCheckoutSession},
 * mock webhook 推送, 验证 invoice 状态 PENDING → PAID.</p>
 */
class WeChatPayE2ETest {

    private MockWeChatPayServer mockServer;
    private WeChatPayAdapter adapter;
    private int port;
    private String mchId = "1900000000";
    private String appId = "wx0000000000000000";
    private String merchantSecret = "test-merchant-secret-e2e";

    private int findFreePort() {
        try {
            java.net.ServerSocket s = new java.net.ServerSocket(0);
            int p = s.getLocalPort();
            s.close();
            return p;
        } catch (java.io.IOException e) {
            return 18199 + (int) (Math.random() * 100);
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        port = findFreePort();

        // Start mock WeChat server
        mockServer = new MockWeChatPayServer(port, mchId, appId, merchantSecret);
        mockServer.start();

        WeChatPayHttpClient httpClient = new WeChatPayHttpClient(
                "http://localhost:" + port, mchId, appId, merchantSecret);
        InMemoryInvoiceStore store = new InMemoryInvoiceStore();
        adapter = new WeChatPayAdapter(httpClient, store);
    }

    @AfterEach
    void tearDown() {
        mockServer.stop();
    }

    @Test
    void fullWeChatPaymentFlow() throws Exception {
        // Step 1: Create JSAPI order
        CheckoutRequest req = new CheckoutRequest(
                "tenant-1", 1000, "CNY", "RAG Pro subscription",
                Map.of("openId", "mock-openid-001"), "https://example.com/success", "https://example.com/cancel"
        );
        CheckoutResult result = adapter.createCheckoutSession(req);

        assertThat(result.invoiceId()).startsWith("INV-");
        assertThat(result.checkoutUrl()).contains("weixin://wxpay/bizpayurl");
        assertThat(result.externalRef()).startsWith("wx");
        assertThat(result.expiresAt()).isPositive();

        // Verify invoice is PENDING
        var pending = adapter.findInvoice("tenant-1", result.invoiceId());
        assertThat(pending).isPresent();
        assertThat(pending.get().status()).isEqualTo(InvoiceStatus.PENDING);
        assertThat(pending.get().amountCents()).isEqualTo(1000);
        assertThat(pending.get().currency()).isEqualTo("CNY");
        assertThat(pending.get().paymentMethod()).isEqualTo(PaymentPort.PaymentMethod.WECHAT);

        // Step 2: Simulate webhook — build payload manually
        // In real flow, MockWeChatPayServer's simulate_webhook POSTs to controller.
        // For test, directly parse + handle via adapter.
        String webhookPayload = String.format(
                "{\"out_trade_no\":\"%s\",\"transaction_id\":\"tx_test_001\","
                        + "\"trade_state\":\"SUCCESS\","
                        + "\"amount\":{\"total\":%d,\"currency\":\"CNY\"},"
                        + "\"success_time\":%d,\"attach\":\"tenant-1\"}",
                result.invoiceId(),
                1000,
                System.currentTimeMillis() / 1000
        );

        var event = adapter.parseWebhookEvent(webhookPayload, "tx_test_001");
        assertThat(event.type()).isEqualTo("wechat.pay.success");
        assertThat(event.tenantId()).isEqualTo("tenant-1");
        assertThat(event.invoiceId()).isEqualTo(result.invoiceId());

        var outcome = adapter.handleWebhook(event);
        assertThat(outcome).isEqualTo(PaymentPort.WebhookOutcome.PROCESSED);

        // Step 3: Verify invoice is PAID
        var paid = adapter.findInvoice("tenant-1", result.invoiceId());
        assertThat(paid).isPresent();
        assertThat(paid.get().status()).isEqualTo(InvoiceStatus.PAID);

        // Step 4: Duplicate webhook → DUPLICATE
        var dupOutcome = adapter.handleWebhook(event);
        assertThat(dupOutcome).isEqualTo(PaymentPort.WebhookOutcome.DUPLICATE);

        // Step 5: Refund
        var refunded = adapter.refund("tenant-1", result.invoiceId(), "customer request");
        assertThat(refunded.status()).isEqualTo(InvoiceStatus.REFUNDED);
    }

    @Test
    void weChatSupportsOnlyCNY() {
        CheckoutRequest req = new CheckoutRequest(
                "tenant-1", 1000, "USD", "USD not supported yee",
                Map.of(), "https://example.com/success", "https://example.com/cancel"
        );
        assertThrows(Exception.class, () -> adapter.createCheckoutSession(req));
    }

    @Test
    void weChatListInvoiceByTenant() {
        for (int i = 0; i < 3; i++) {
            CheckoutRequest req = new CheckoutRequest(
                    "t-wx-list", 100, "CNY", "wx item " + i,
                    Map.of("openId", "u1"), "https://example.com/success", "https://example.com/cancel"
            );
            adapter.createCheckoutSession(req);
        }
        assertThat(adapter.listByTenant("t-wx-list", 10)).hasSize(3);
    }
}
