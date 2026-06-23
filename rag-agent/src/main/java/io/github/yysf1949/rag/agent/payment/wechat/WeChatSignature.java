package io.github.yysf1949.rag.agent.payment.wechat;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public class WeChatSignature {

    public static String sign(String method, String path, String timestamp,
                              String nonceStr, String body, String merchantSecret) {
        String message = method + "\n" + path + "\n" + timestamp + "\n" + nonceStr + "\n" + body + "\n";
        return hmacSha256Hex(merchantSecret, message);
    }

    public static boolean verifyWebhook(String timestamp, String nonceStr, String body,
                                        String receivedHex, String merchantSecret) {
        String message = timestamp + "\n" + nonceStr + "\n" + body + "\n";
        String expected = hmacSha256Hex(merchantSecret, message);
        return constantTimeEquals(expected, receivedHex);
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
}
