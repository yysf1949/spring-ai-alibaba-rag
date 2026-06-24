package io.github.yysf1949.rag.agent.payment.stripe;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StripeSignatureVerifier 单测 — HMAC 签名 + 验签 + 时间戳容差.
 */
class StripeSignatureVerifierTest {

    @Test
    void signAndVerifyRoundTrips() {
        StripeSignatureVerifier v = new StripeSignatureVerifier("whsec_test_secret");
        String payload = "{\"id\": \"evt_test\"}";
        long now = System.currentTimeMillis() / 1000;
        String sig = v.sign(now, payload);
        assertThat(v.verify(sig, payload)).isTrue();
    }

    @Test
    void verifyWithWrongPayloadFails() {
        StripeSignatureVerifier v = new StripeSignatureVerifier("whsec_test_secret");
        long now = System.currentTimeMillis() / 1000;
        String sig = v.sign(now, "real_payload");
        assertThat(v.verify(sig, "wrong_payload")).isFalse();
    }

    @Test
    void verifyWithStaleTimestampFails() {
        StripeSignatureVerifier v = new StripeSignatureVerifier("whsec_test_secret", 5);
        long old = System.currentTimeMillis() / 1000 - 3600;  // 1 hour ago
        String sig = v.sign(old, "payload");
        assertThat(v.verify(sig, "payload")).isFalse();
    }

    @Test
    void verifyWithNullHeaderFails() {
        StripeSignatureVerifier v = new StripeSignatureVerifier("whsec_test_secret");
        assertThat(v.verify(null, "payload")).isFalse();
        assertThat(v.verify("", "payload")).isFalse();
    }

    @Test
    void hmacSha256HexProducesCorrectLength() {
        String hex = StripeSignatureVerifier.hmacSha256Hex("key", "data");
        assertThat(hex).hasSize(64);  // SHA-256 = 32 bytes = 64 hex chars
    }
}
