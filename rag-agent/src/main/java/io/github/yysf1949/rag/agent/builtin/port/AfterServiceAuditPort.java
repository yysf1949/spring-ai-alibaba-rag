package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

/**
 * 售后善后审计仓库端口 — 定义存储契约。
 *
 * <h2>设计意图</h2>
 * <p>售后善后操作（退款确认、取消确认、投诉升级）需要完整的审计链路，
 * 便于事后追溯和合规检查。遵循六边形架构，Tool 通过此 Port 与存储交互。</p>
 *
 * <h2>升级路径</h2>
 * <p>生产可换 MySQL/Postgres + Flyway 迁移。本 Phase 范围使用 InMemory 实现。</p>
 */
public interface AfterServiceAuditPort {

    /**
     * 保存审计记录。
     *
     * @param record 审计记录
     * @return 保存后的记录
     */
    AuditRecord save(AuditRecord record);

    /**
     * 根据订单 ID 查询审计记录。
     *
     * @param orderId 订单 ID
     * @return 匹配的审计记录列表
     */
    List<AuditRecord> findByOrder(String orderId);

    /**
     * 售后善后审计持久化记录。
     *
     * @param auditId    审计 ID（主键）
     * @param orderId    订单 ID
     * @param actionType 操作类型（REFUND_CONFIRMED / CANCEL_CONFIRMED / COMPLAINT_ESCALATED）
     * @param steps      执行步骤描述列表
     * @param success    是否全部成功
     * @param createdAt  创建时间戳（毫秒）
     */
    record AuditRecord(
            String auditId,
            String orderId,
            String actionType,
            List<String> steps,
            boolean success,
            long createdAt
    ) {}
}
