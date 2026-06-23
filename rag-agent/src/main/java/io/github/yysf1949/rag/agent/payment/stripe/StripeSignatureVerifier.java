package io.github.yysf1949.rag.agent.payment.stripe;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;

/**
 * Stripe webhook 签名验证器 — Phase 40 T4.
 *
 * <h2>Stripe 签名格式</h2>
 * <pre>
 * Stripe-Signature: t=1700000000,v1=5257a869e7ecebeda32affa62cdca3fa51cad7e77a0e56ff536d0ce8e108d8bd
 * </pre>
 *
 * <p>校验: HMAC-SHA256(secret, "{t}.{rawBody}") 与 v1= 后面的 hex 比对.
 * 同时检查时间戳偏移不超过 toleranceSeconds (默认 300).</p>
 *
 * <h2>为什么不依赖 stripe-java</h2>
 * <p>签名算法只用 javax.crypto 标准库, ~30 行实现, 跟 {@link StripeHttpClient} 风格一致.
 * 真生产可换 stripe-java 的 {@code Event.constructEvent()}.</p>
 */
public class StripeSignatureVerifier {

    private final String webhookSecret;
    private final long toleranceSeconds;

    public StripeSignatureVerifier(String webhookSecret) {
        this(webhookSecret, 300);
    }

    public StripeSignatureVerifier(String webhookSecret, long toleranceSeconds) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            throw new IllegalArgumentException("webhookSecret required");
        }
        this.webhookSecret = webhookSecret;
        this.toleranceSeconds = toleranceSeconds;
    }

    /**
     * 验证 Stripe-Signature header.
     *
     * @param signatureHeader Stripe-Signature header 值 (e.g. {@code "t=...,v1=..."})
     * @param payload         原始请求体 (字符串, 非解析后 JSON)
     * @return true=valid, false=invalid
     */
    public boolean verify(String signatureHeader, String payload) {
        if (signatureHeader == null || signatureHeader.isBlank()) {
            return false;
        }
        Parsed parsed = parse(signatureHeader);
        if (parsed == null) {
            return false;
        }
        // 1. 时间戳 tolerance check
        long now = Instant.now().getEpochSecond();
        if (Math.abs(now - parsed.timestamp) > toleranceSeconds) {
            return false;
        }
        // 2. HMAC-SHA256 计算
        String signedPayload = parsed.timestamp + "." + payload;
        String expected = hmacSha256Hex(webhookSecret, signedPayload);
        // 3. constant-time compare
        return constantTimeEquals(expected, parsed.v1Signature);
    }

    /**
     * 工具方法: 签一个 payload (给 MockStripeServer 发回调用).
     */
    public String sign(long timestamp, String payload) {
        String signedPayload = timestamp + "." + payload;
        return "t=" + timestamp + ",v1=" + hmacSha256Hex(webhookSecret, signedPayload);
    }

    public static String hmacSha256Hex(String secret, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] result = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return toHex(result);
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }

    static String toHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] ab = a.getBytes(StandardCharsets.UTF_8);
        byte[] bb = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(ab, bb);
    }

    static Parsed parse(String header) {
        long timestamp = -1;
        String v1 = null;
        for (String part : header.split(",")) {
            String[] kv = part.trim().split("=", 2);
            if (kv.length != 2) continue;
            if ("t".equals(kv[0])) {
                try { timestamp = Long.parseLong(kv[1]); } catch (NumberFormatException ignored) {}
            } else if ("v1".equals(kv[0])) {
                v1 = kv[1];
            }
        }
        if (timestamp < 0 || v1 == null) return null;
        return new Parsed(timestamp, v1);
    }

    record Parsed(long timestamp, String v1Signature) { }
}