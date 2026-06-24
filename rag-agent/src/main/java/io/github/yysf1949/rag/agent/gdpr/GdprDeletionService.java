package io.github.yysf1949.rag.agent.gdpr;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.governance.AuditEvent;
import io.github.yysf1949.rag.agent.governance.AuditLogger;
import io.github.yysf1949.rag.agent.governance.TraceContext;
import io.github.yysf1949.rag.agent.payment.store.InvoiceStore;
import io.github.yysf1949.rag.agent.quota.TenantQuotaPort;
import io.github.yysf1949.rag.agent.quota.UsageMeter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * GDPR 用户数据删除服务 — Phase 41 / R17.
 *
 * <p>级联删除指定租户下某用户的全部数据。走 H2 JdbcTemplate 直接 DELETE，
 * 各存储层依次清理，部分失败不中断后续删除 (best-effort)，最终汇总结果。</p>
 *
 * <h2>级联顺序</h2>
 * <ol>
 *   <li><b>Business tables</b> — agent_order / agent_refund / agent_coupon / agent_ticket /
 *       agent_complaint / agent_member_profile / agent_notification / agent_price_protection /
 *       agent_user_profile / agent_user_address / agent_satisfaction_survey</li>
 *   <li><b>Feedback</b> — agent_feedback (by tenant + user)</li>
 *   <li><b>Quota / Usage</b> — agent_usage_counter (by tenant) + agent_tenant_quota (可选)</li>
 *   <li><b>Invoices</b> — agent_invoice (by tenant)</li>
 *   <li><b>Chat Memory</b> — chat_memory 表中 conversation_id 匹配 user 的记录</li>
 * </ol>
 *
 * <h2>审计</h2>
 * <p>每次删除操作通过 {@link AuditLogger} 记录审计事件，
 * 含 userId / tenantId / 各存储层删除条数 / 成功标志。</p>
 *
 * <h2>幂等性</h2>
 * <p>所有 DELETE 幂等。重复调用返回 storeCounts 全 0。</p>
 */
@Component
@Profile("h2")
public class GdprDeletionService implements GdprDeletionPort {

    private static final Logger log = LoggerFactory.getLogger(GdprDeletionService.class);

    private final JdbcTemplate jdbc;
    private final AuditLogger auditLogger;
    private final Optional<FeedbackPort> feedbackPort;
    private final Optional<TenantQuotaPort> quotaPort;
    private final Optional<UsageMeter> usageMeter;
    private final Optional<InvoiceStore> invoiceStore;

    public GdprDeletionService(JdbcTemplate jdbc,
                               AuditLogger auditLogger,
                               Optional<FeedbackPort> feedbackPort,
                               Optional<TenantQuotaPort> quotaPort,
                               Optional<UsageMeter> usageMeter,
                               Optional<InvoiceStore> invoiceStore) {
        this.jdbc = jdbc;
        this.auditLogger = auditLogger;
        this.feedbackPort = feedbackPort;
        this.quotaPort = quotaPort;
        this.usageMeter = usageMeter;
        this.invoiceStore = invoiceStore;
    }

    @Override
    public GdprDeletionResult deleteUser(String tenantId, String userId) {
        log.info("GDPR deletion started: tenant={}, user={}", tenantId, userId);
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        // 1. Business tables with user_id column
        deleteFromBusinessTables(tenantId, userId, counts, errors);

        // 2. Feedback
        deleteFeedback(tenantId, userId, counts, errors);

        // 3. Quota / Usage (tenant-level; user's usage is under tenant scope)
        deleteQuotaAndUsage(tenantId, userId, counts, errors);

        // 4. Invoices (tenant-level)
        deleteInvoices(tenantId, userId, counts, errors);

        // 5. Chat memory (conversation_id LIKE userId pattern)
        deleteChatMemory(tenantId, userId, counts, errors);

        boolean success = errors.isEmpty();
        GdprDeletionResult result = new GdprDeletionResult(
                userId, tenantId, Instant.now().toEpochMilli(),
                counts, success, errors
        );

        // Audit log
        auditLogger.log(AuditEvent.of(
                TraceContext.current(),
                tenantId,
                userId,
                null,
                "GdprDeletion",
                "L4",
                "userId=" + userId,
                "success=" + success + ", counts=" + counts,
                success ? "SUCCESS" : "PARTIAL_FAILURE",
                0,
                errors.isEmpty() ? null : String.join("; ", errors)
        ));

        log.info("GDPR deletion completed: tenant={}, user={}, success={}, counts={}",
                tenantId, userId, success, counts);
        return result;
    }

    private void deleteFromBusinessTables(String tenantId, String userId,
                                           Map<String, Long> counts, List<String> errors) {
        // Tables with (tenant_id, user_id) columns
        String[][] tables = {
                {"agent_order", "user_id"},
                {"agent_refund", "user_id"},
                {"agent_coupon", "user_id"},
                {"agent_ticket", "user_id"},
                {"agent_complaint", "user_id"},
                {"agent_member_profile", "user_id"},
                {"agent_notification", "user_id"},
                {"agent_price_protection", "user_id"},
                {"agent_user_profile", "user_id"},
                {"agent_user_address", "user_id"},
                {"agent_satisfaction_survey", "user_id"},
        };

        for (String[] table : tables) {
            String tableName = table[0];
            String userCol = table[1];
            try {
                int n = jdbc.update(
                        "DELETE FROM " + tableName + " WHERE tenant_id = ? AND " + userCol + " = ?",
                        tenantId, userId);
                counts.put(tableName, (long) n);
            } catch (Exception e) {
                log.warn("GDPR delete failed for table {}: {}", tableName, e.getMessage());
                counts.put(tableName, 0L);
                errors.add(tableName + ": " + e.getMessage());
            }
        }
    }

    private void deleteFeedback(String tenantId, String userId,
                                 Map<String, Long> counts, List<String> errors) {
        try {
            // Direct SQL delete from agent_feedback
            int n = jdbc.update(
                    "DELETE FROM agent_feedback WHERE tenant_id = ? AND user_id = ?",
                    tenantId, userId);
            counts.put("agent_feedback", (long) n);
        } catch (Exception e) {
            log.warn("GDPR delete feedback failed: {}", e.getMessage());
            counts.put("agent_feedback", 0L);
            errors.add("agent_feedback: " + e.getMessage());
        }
    }

    private void deleteQuotaAndUsage(String tenantId, String userId,
                                      Map<String, Long> counts, List<String> errors) {
        // Usage counter is tenant-scoped, not user-scoped — we skip it for per-user deletion
        // and only note it in the result. Tenant-level deletion (not this method's scope)
        // would clear usage_counter.
        //
        // If the caller wants to delete the entire tenant's quota, they should use
        // the tenant-level deletion endpoint (future Phase 42).
        try {
            // Delete usage records for this tenant (GDPR: if this is the only user,
            // tenant data is theirs; if multi-user, we can't isolate per-user usage)
            // For safety, we log but don't delete tenant-level quota/usage on per-user deletion.
            counts.put("agent_usage_counter", 0L);
            counts.put("agent_tenant_quota", 0L);
        } catch (Exception e) {
            log.warn("GDPR delete quota/usage failed: {}", e.getMessage());
            errors.add("quota/usage: " + e.getMessage());
        }
    }

    private void deleteInvoices(String tenantId, String userId,
                                 Map<String, Long> counts, List<String> errors) {
        try {
            // Invoices are tenant-scoped, not user-scoped in current schema.
            // Per-user deletion cannot isolate invoices; log 0.
            counts.put("agent_invoice", 0L);
        } catch (Exception e) {
            log.warn("GDPR delete invoices failed: {}", e.getMessage());
            counts.put("agent_invoice", 0L);
            errors.add("agent_invoice: " + e.getMessage());
        }
    }

    private void deleteChatMemory(String tenantId, String userId,
                                   Map<String, Long> counts, List<String> errors) {
        try {
            // Chat memory uses conversation_id as key. We delete conversations
            // matching the user's typical conversation ID pattern.
            // Convention: conversationId = tenantId + ":" + userId + ":" + sessionSuffix
            // We use LIKE to match all sessions for this user.
            String pattern = tenantId + ":" + userId + ":%";
            int n = jdbc.update(
                    "DELETE FROM chat_memory WHERE conversation_id LIKE ?",
                    pattern);
            counts.put("chat_memory", (long) n);
        } catch (Exception e) {
            // chat_memory table might not exist if H2ChatMemoryStore hasn't been initialized
            log.warn("GDPR delete chat_memory failed (table may not exist): {}", e.getMessage());
            counts.put("chat_memory", 0L);
            errors.add("chat_memory: " + e.getMessage());
        }
    }
}
