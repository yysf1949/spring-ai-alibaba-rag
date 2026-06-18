package io.github.yysf1949.rag.agent.governance;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * 幂等键 — 对齐「路条编程」文章 §"幂等是 AI 客服第一课"：
 *
 * <blockquote>
 * 每一个写操作工具都应该接收一个稳定的业务幂等键，而不是每次调用都
 * 临时生成新的请求 ID。幂等键可以由会话 ID、用户确认动作和业务对象
 * 共同生成，并写入 Redis 或数据库唯一索引。
 * </blockquote>
 *
 * <h2>构成</h2>
 * <pre>
 *   {tenantId}:{userId}:{sessionId}:{toolName}:{idempotencyToken}
 * </pre>
 *
 * <h2>{@code idempotencyToken} 来源</h2>
 * <p>由调用方提供（前端按钮点击 → 后端生成 token → 调用 Agent）。同一个
 * 用户操作同一个业务对象，整个会话期间 token 必须稳定。</p>
 */
public record IdempotencyKey(
        String tenantId,
        String userId,
        String sessionId,
        String toolName,
        String rawToken,
        String hash
) {

    public static IdempotencyKey of(String tenantId, String userId, String sessionId,
                                    String toolName, String idempotencyToken) {
        if (idempotencyToken == null || idempotencyToken.isBlank()) {
            throw new IllegalArgumentException("idempotencyToken must not be blank");
        }
        String composite = String.join(":", tenantId, userId,
                sessionId == null ? "_" : sessionId,
                toolName,
                idempotencyToken);
        return new IdempotencyKey(tenantId, userId, sessionId, toolName,
                idempotencyToken, sha256(composite));
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}