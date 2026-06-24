package io.github.yysf1949.rag.agent.gdpr;

/**
 * GDPR 用户数据删除端口 — Phase 41 / R17.
 *
 * <p>实现"被遗忘权" (Right to Erasure, GDPR Article 17) —
 * 级联删除指定用户在所有存储层的数据，包括：</p>
 *
 * <ul>
 *   <li><b>Chunks</b> — 向量索引中的文档分块 (via VectorStore)</li>
 *   <li><b>Chat Memory</b> — 对话历史 (via ChatMemoryRepository)</li>
 *   <li><b>Audit Logs</b> — 审计日志中的用户关联记录 (H2 表)</li>
 *   <li><b>Feedback</b> — 用户反馈记录 (via FeedbackPort)</li>
 *   <li><b>Quota / Usage</b> — 租户配额和用量计数 (via TenantQuotaPort / UsageMeter)</li>
 *   <li><b>Invoices</b> — 支付发票记录 (via InvoiceStore)</li>
 *   <li><b>Business Tables</b> — 订单/退款/优惠券/工单等业务表 (H2 DELETE)</li>
 * </ul>
 *
 * <h2>设计意图</h2>
 * <p>端口模式，实现可换。默认实现 {@code GdprDeletionService} 走 H2 JdbcTemplate
 * 直接删除；生产实现可加异步队列 + 确认回调 + 数据库事务。</p>
 *
 * <h2>幂等性</h2>
 * <p>同一用户重复调用删除是安全的 — 所有 DELETE 操作都是幂等的
 * (删除不存在的行不会报错)。返回的 storeCounts 反映实际删除行数
 * (第二次调用应为全 0)。</p>
 *
 * <h2>审计</h2>
 * <p>每次调用必须通过 {@link io.github.yysf1949.rag.agent.governance.AuditLogger}
 * 记录审计事件，包含 userId / tenantId / 删除结果摘要。</p>
 */
public interface GdprDeletionPort {

    /**
     * 级联删除指定租户下某用户的全部数据.
     *
     * @param tenantId 租户 ID (硬隔离)
     * @param userId   用户 ID
     * @return 删除结果 (含各存储层删除条数)
     */
    GdprDeletionResult deleteUser(String tenantId, String userId);
}
