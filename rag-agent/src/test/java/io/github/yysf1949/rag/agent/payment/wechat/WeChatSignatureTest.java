package io.github.yysf1949.rag.agent.payment.wechat;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WeChatSignatureTest {

    @Test
    void signAndVerifyWebhookRoundTrips() {
        String secret = "test-merchant-secret";
        long now = System.currentTimeMillis() / 1000;
        String nonce = "test-nonce-123";
        String body = "{\"out_trade_no\": \"INV-001\"}";

        // The webhook verify format is: timestamp + "\n" + nonceStr + "\n" + body + "\n"
        String expected = WeChatSignature.hmacSha256Hex(secret,
                now + "\n" + nonce + "\n" + body + "\n");

        boolean valid = WeChatSignature.verifyWebhook(
                String.valueOf(now), nonce, body, expected, secret);
        assertThat(valid).isTrue();
    }

    @Test
    void signForApiRequestProducesCorrectFormat() {
        String secret = "test-secret";
        long now = System.currentTimeMillis() / 1000;
        String nonce = "nonce-456";
        String body = "{\"amount\": 100}";
        String method = "POST";
        String path = "/v3/pay/transactions/jsapi";

        String sig = WeChatSignature.sign(method, path, String.valueOf(now), nonce, body, secret);
        assertThat(sig).hasSize(64);

        // Verify by recomputing with the same format
        String expected = WeChatSignature.hmacSha256Hex(secret,
                method + "\n" + path + "\n" + now + "\n" + nonce + "\n" + body + "\n");
        assertThat(sig).isEqualTo(expected);
    }

    @Test
    void hmacSha256HexProducesCorrectLength() {
        String hex = WeChatSignature.hmacSha256Hex("key", "data");
        assertThat(hex).hasSize(64);
    }

    @Test
    void verifyWebhookWithWrongSecretFails() {
        String secret1 = "correct-secret";
        String secret2 = "wrong-secret";
        long now = System.currentTimeMillis() / 1000;
        String nonce = "test-nonce";
        String body = "{}";
        String expected = WeChatSignature.hmacSha256Hex(secret1,
                now + "\n" + nonce + "\n" + body + "\n");
        assertThat(WeChatSignature.verifyWebhook(
                String.valueOf(now), nonce, body, expected, secret2)).isFalse();
    }
}
