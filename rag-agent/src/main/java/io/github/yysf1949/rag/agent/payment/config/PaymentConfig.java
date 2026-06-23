package io.github.yysf1949.rag.agent.payment.config;

import io.github.yysf1949.rag.agent.payment.stripe.StripeHttpClient;
import io.github.yysf1949.rag.agent.payment.stripe.StripeSignatureVerifier;
import io.github.yysf1949.rag.agent.payment.wechat.WeChatPayHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 支付客户端配置 — Phase 40 T4.
 *
 * <p>把 Stripe / WeChat HTTP client 和 signature verifier 装配为 Spring bean.
 * 从 {@code application.yml} 读 API key / secret / base URL.</p>
 *
 * <h2>默认配置</h2>
 * <ul>
 *   <li>{@code rag.payment.stripe.api-base} — 默认 {@code http://localhost:0} (无 mock server 时会 fail)</li>
 *   <li>{@code rag.payment.stripe.secret-key} — 默认 {@code sk_test_mock}</li>
 *   <li>{@code rag.payment.stripe.webhook-secret} — 默认 {@code whsec_test_mock}</li>
 *   <li>{@code rag.payment.wechat.api-base} — 默认 {@code http://localhost:0}</li>
 *   <li>{@code rag.payment.wechat.mch-id} — 默认 {@code 1900000000}</li>
 *   <li>{@code rag.payment.wechat.app-id} — 默认 {@code wx0000000000000000}</li>
 *   <li>{@code rag.payment.wechat.merchant-secret} — 默认 {@code test-merchant-secret}</li>
 * </ul>
 *
 * <p>真生产请覆盖这些值. 测试场景用 {@code @TestConfiguration} 提供本地 mock.</p>
 */
@Configuration
public class PaymentConfig {

    @Bean
    public StripeHttpClient stripeHttpClient(
            @Value("${rag.payment.stripe.api-base:http://localhost:0}") String apiBase,
            @Value("${rag.payment.stripe.secret-key:sk_test_mock}") String secretKey) {
        return new StripeHttpClient(apiBase, secretKey);
    }

    @Bean
    public StripeSignatureVerifier stripeSignatureVerifier(
            @Value("${rag.payment.stripe.webhook-secret:whsec_test_mock}") String webhookSecret) {
        return new StripeSignatureVerifier(webhookSecret);
    }

    @Bean
    public WeChatPayHttpClient weChatPayHttpClient(
            @Value("${rag.payment.wechat.api-base:http://localhost:0}") String apiBase,
            @Value("${rag.payment.wechat.mch-id:1900000000}") String mchId,
            @Value("${rag.payment.wechat.app-id:wx0000000000000000}") String appId,
            @Value("${rag.payment.wechat.merchant-secret:test-merchant-secret}") String merchantSecret) {
        return new WeChatPayHttpClient(apiBase, mchId, appId, merchantSecret);
    }
}
