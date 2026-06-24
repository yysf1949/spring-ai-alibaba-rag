package io.github.yysf1949.rag.agent.gdpr;

import java.util.Map;

/**
 * GDPR 用户删除结果 — 记录级联删除后每个存储层清理的记录数.
 *
 * <p>Phase 41 / R17 — 用于审计和合规报告。每次删除操作生成一份
 * {@link GdprDeletionResult}，持久化到审计日志中，确保"被遗忘权"
 * 执行过程可追溯。</p>
 *
 * @param userId       被删除的用户 ID
 * @param tenantId     租户 ID
 * @param deletedAt    删除时间戳 (epoch millis)
 * @param storeCounts  各存储层清理记录数 (key=存储名, value=删除条数)
 * @param success      全部级联是否成功 (部分失败时 false)
 * @param errors       部分失败的错误信息列表 (全部成功时为空 list)
 */
public record GdprDeletionResult(
        String userId,
        String tenantId,
        long deletedAt,
        Map<String, Long> storeCounts,
        boolean success,
        java.util.List<String> errors
) {
}
