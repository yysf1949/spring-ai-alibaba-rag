package io.github.yysf1949.rag.agent.gdpr;

/**
 * GDPR "Right to be Forgotten" 删除服务端口 — Phase 41 T1 (R17).
 *
 * <p>GDPR Article 17 要求数据控制者在收到用户删除请求后,
 * 在合理期限内删除该用户的全部个人数据及其副本.</p>
 *
 * <h2>Cascade 范围</h2>
 * <ul>
 *   <li><b>用户级</b> ({@link #deleteUserData}): 向量 chunks + 会话记忆 + 用户反馈 + 审计日志</li>
 *   <li><b>租户级</b> ({@link #deleteTenantData}): 上述全部 + 配额 + 发票 (admin 全量删除)</li>
 * </ul>
 *
 * <h2>安全保证</h2>
 * <ul>
 *   <li>操作幂等 — 重复调用同一 (tenantId, userId) 返回 0 deleted</li>
 *   <li>审计追踪 — 删除操作本身记录审计日志 (谁删了谁, 删了多少, 何时)</li>
 *   <li>不删 KB 文档 — KB 文档属于租户资产, 非用户个人数据; 仅删用户产生的交互数据</li>
 * </ul>
 */
public interface GdprDeletionService {

    /**
     * 删除指定用户的全部个人数据 (GDPR Article 17).
     *
     * @param tenantId 租户 ID
     * @param userId   被删除用户 ID
     * @return 删除结果统计
     */
    GdprDeletionResult deleteUserData(String tenantId, String userId);

    /**
     * 删除指定租户的全部数据 (admin 级全量删除 — 合同终止 / 数据出境违规).
     * <p>包含用户级全部数据 + 租户级配额 + 发票.</p>
     *
     * @param tenantId 被删除租户 ID
     * @return 删除结果统计
     */
    GdprDeletionResult deleteTenantData(String tenantId);
}
