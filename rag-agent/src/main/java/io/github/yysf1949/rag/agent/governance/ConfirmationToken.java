package io.github.yysf1949.rag.agent.governance;

/**
 * 用户确认令牌 — 高风险写操作的"两阶段提交"。
 *
 * <h2>对齐文章"人工确认是设计的一部分"</h2>
 * <p>文章明确要求：创建退款等写操作"只有用户已经明确确认，
 * 并且提供有效确认令牌时才能调用"。</p>
 *
 * <h2>流程</h2>
 * <ol>
 *   <li>Agent 查询后告知用户"您确认要退款 ¥50 吗？"</li>
 *   <li>用户说"确认" → Agent 调用 {@code ConfirmationService.generate()}</li>
 *   <li>Agent 调用 write 工具时携带 confirmationToken</li>
 *   <li>RiskGate 校验 token 有效且未过期</li>
 * </ol>
 *
 * @param rawToken     令牌字符串
 * @param toolName     绑定的工具名（token 只对这个工具有效）
 * @param userId       绑定的用户（防止跨用户重放）
 * @param expiresAt    过期时间戳（ms）
 */
public record ConfirmationToken(
        String rawToken,
        String toolName,
        String userId,
        long expiresAt
) {
    /** 检查 token 是否过期 */
    public boolean isExpired() {
        return System.currentTimeMillis() > expiresAt;
    }

    /** 检查 token 是否匹配指定工具和用户 */
    public boolean matches(String toolName, String userId) {
        return this.toolName.equals(toolName) && this.userId.equals(userId);
    }
}
