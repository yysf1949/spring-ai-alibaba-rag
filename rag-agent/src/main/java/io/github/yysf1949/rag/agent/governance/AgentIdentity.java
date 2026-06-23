package io.github.yysf1949.rag.agent.governance;

import java.util.Set;

/**
 * Agent 调用的身份上下文 — 在会话入口处由 HTTP filter 解析，
 * 透传到编排层 → 治理层 → 工具实现。
 *
 * <p>对齐「路条编程」文章 §"AI Agent 不能绕过原有业务规则"：
 * "如果一个普通用户只能查看自己的订单，那么 Agent 也只能以这个用户的
 * 身份查看自己的订单，不能因为 Agent 使用了后台服务账号，就获得
 * 查询所有订单的能力。"</p>
 *
 * <p>对齐本项目设计原则 §6 租户硬墙：{@code tenantId} 永不跨用户。</p>
 *
 * @param tenantId           租户 ID（永不跨用户）
 * @param userId             终端用户 ID
 * @param sessionId          会话 ID（用于幂等键拼接）
 * @param roles              角色列表（用于风险门控 / RBAC）
 * @param confirmationToken  确认令牌（Phase 21: 高风险写操作前用户确认的令牌）
 */
public record AgentIdentity(
        String tenantId,
        String userId,
        String sessionId,
        Set<String> roles,
        String confirmationToken
) {

    /** 便捷构造器 — 不带确认令牌 */
    public AgentIdentity(String tenantId, String userId, String sessionId, Set<String> roles) {
        this(tenantId, userId, sessionId, roles, null);
    }

    /** 带确认令牌的构造器 */
    public AgentIdentity withConfirmationToken(String token) {
        return new AgentIdentity(tenantId, userId, sessionId, roles, token);
    }

    public AgentIdentity {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId must not be blank");
        }
        if (userId == null || userId.isBlank()) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
    }
}
