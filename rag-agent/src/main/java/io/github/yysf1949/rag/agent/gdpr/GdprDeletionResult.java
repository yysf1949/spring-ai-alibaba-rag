package io.github.yysf1949.rag.agent.gdpr;

/**
 * GDPR "Right to be Forgotten" 删除结果 — Phase 41 T1 (R17).
 *
 * <p>记录 cascade 删除各数据源的命中行数, 供审计日志 + API 响应使用.</p>
 *
 * @param tenantId        租户 ID
 * @param userId          被删除的用户 ID
 * @param chunksDeleted   向量索引中删除的 chunk 数
 * @param memoryDeleted   删除的会话记忆条数
 * @param feedbackDeleted 删除的用户反馈条数
 * @param auditLogsDeleted 删除的审计日志条数
 * @param quotaDeleted    删除的配额记录数 (仅 deleteTenant=true 时非零)
 * @param invoicesDeleted 删除的发票记录数 (仅 deleteTenant=true 时非零)
 * @param deleteTenant    是否连同租户级数据一起删除 (admin 全量删除)
 * @param durationMs      删除操作总耗时 (毫秒)
 */
public record GdprDeletionResult(
        String tenantId,
        String userId,
        int chunksDeleted,
        int memoryDeleted,
        int feedbackDeleted,
        int auditLogsDeleted,
        int quotaDeleted,
        int invoicesDeleted,
        boolean deleteTenant,
        long durationMs
) {
    /** 总删除条数 (不含 chunks, chunks 是向量索引级) */
    public int totalRecordsDeleted() {
        return memoryDeleted + feedbackDeleted + auditLogsDeleted
                + quotaDeleted + invoicesDeleted;
    }
}
