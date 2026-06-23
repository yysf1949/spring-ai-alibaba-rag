package io.github.yysf1949.rag.agent.governance;

import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确认令牌服务 — 生成和验证确认令牌。
 *
 * <h2>设计决策</h2>
 * <ul>
 *   <li>token 有效期 5 分钟（防止长时间悬挂）</li>
 *   <li>token 绑定 toolName + userId（防跨工具/跨用户重放）</li>
 *   <li>token 一次性使用（验证后自动失效）</li>
 *   <li>InMemory 实现，生产可换 Redis</li>
 * </ul>
 */
@Component
public class ConfirmationService {

    /** token 有效期 5 分钟 */
    public static final long TOKEN_TTL_MS = 5 * 60 * 1000L;

    private final Map<String, ConfirmationToken> activeTokens = new ConcurrentHashMap<>();

    /**
     * 生成确认令牌。
     *
     * @param toolName 要确认的工具名
     * @param userId   确认的用户
     * @return 生成的令牌
     */
    public ConfirmationToken generate(String toolName, String userId) {
        String raw = "CONF-" + UUID.randomUUID().toString().substring(0, 12);
        long expiresAt = System.currentTimeMillis() + TOKEN_TTL_MS;
        var token = new ConfirmationToken(raw, toolName, userId, expiresAt);
        activeTokens.put(raw, token);
        return token;
    }

    /**
     * 验证并消费令牌（一次性）。
     *
     * @param rawToken 令牌字符串
     * @param toolName 期望的工具名
     * @param userId   期望的用户
     * @return 令牌如果有效则返回，否则返回 null
     */
    public ConfirmationToken validateAndConsume(String rawToken, String toolName, String userId) {
        if (rawToken == null || rawToken.isBlank()) return null;
        ConfirmationToken token = activeTokens.remove(rawToken);
        if (token == null) return null;
        if (token.isExpired()) return null;
        if (!token.matches(toolName, userId)) return null;
        return token;
    }

    /** 清理过期 token（可定时调用） */
    public int cleanup() {
        int before = activeTokens.size();
        activeTokens.values().removeIf(ConfirmationToken::isExpired);
        return before - activeTokens.size();
    }

    /** 当前活跃 token 数（测试用） */
    public int activeCount() {
        return activeTokens.size();
    }
}
