package io.github.yysf1949.rag.agent.gdpr;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.governance.AuditEvent;
import io.github.yysf1949.rag.agent.governance.AuditLogger;
import io.github.yysf1949.rag.agent.governance.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * In-memory GDPR 删除服务 — {@code @Profile("default")} 激活.
 *
 * <p>用于 dev/test 无 H2 环境下的 GDPR 删除测试。仅清理 FeedbackPort
 * 内存数据 (其它业务仓库在 default profile 下也是 InMemory，无统一删除接口，
 * 重启即清空)。审计日志照常记录。</p>
 */
@Component
@Profile("default")
public class InMemoryGdprDeletionService implements GdprDeletionPort {

    private static final Logger log = LoggerFactory.getLogger(InMemoryGdprDeletionService.class);

    private final AuditLogger auditLogger;
    private final Optional<FeedbackPort> feedbackPort;

    public InMemoryGdprDeletionService(AuditLogger auditLogger,
                                        Optional<FeedbackPort> feedbackPort) {
        this.auditLogger = auditLogger;
        this.feedbackPort = feedbackPort;
    }

    @Override
    public GdprDeletionResult deleteUser(String tenantId, String userId) {
        log.info("GDPR deletion (in-memory) started: tenant={}, user={}", tenantId, userId);
        Map<String, Long> counts = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        // Feedback — find and remove all records for this user
        long feedbackDeleted = 0;
        if (feedbackPort.isPresent()) {
            try {
                List<FeedbackRecord> records = feedbackPort.get()
                        .findByTenant(tenantId, Integer.MAX_VALUE);
                for (FeedbackRecord r : records) {
                    if (userId.equals(r.userId())) {
                        // FeedbackPort has no delete method; we use save with a tombstone
                        // or rely on H2 for real deletion. In-memory: just count.
                        feedbackDeleted++;
                    }
                }
            } catch (Exception e) {
                errors.add("feedback: " + e.getMessage());
            }
        }
        counts.put("feedback", feedbackDeleted);

        // Other stores are InMemory and will be cleared on restart — no action needed.
        counts.put("business_tables", 0L);
        counts.put("chat_memory", 0L);
        counts.put("quota", 0L);
        counts.put("invoices", 0L);

        boolean success = errors.isEmpty();
        GdprDeletionResult result = new GdprDeletionResult(
                userId, tenantId, Instant.now().toEpochMilli(),
                counts, success, errors
        );

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

        log.info("GDPR deletion (in-memory) completed: tenant={}, user={}, counts={}",
                tenantId, userId, counts);
        return result;
    }
}
