package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.AfterServiceAuditPort;
import io.github.yysf1949.rag.agent.builtin.port.NotificationRepositoryPort;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 售后善后工具 — L3 业务态写操作。
 *
 * <p>退款确认、取消确认、投诉升级等售后场景的善后处理：记录审计 + 发通知。</p>
 */
@Component
public class AfterServiceTool {

    private final AfterServiceAuditPort auditRepo;
    private final NotificationRepositoryPort notificationRepo;
    private final IdempotencyStore idempotencyStore;

    public AfterServiceTool(AfterServiceAuditPort auditRepo, NotificationRepositoryPort notificationRepo,
                            IdempotencyStore idempotencyStore) {
        this.auditRepo = Objects.requireNonNull(auditRepo, "auditRepo");
        this.notificationRepo = Objects.requireNonNull(notificationRepo, "notificationRepo");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
    }

    @ToolSpec(
            name = "execute_after_service",
            description = "执行售后善后操作（退款确认/取消确认/投诉升级），记录审计链路并发送用户通知。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = true,
            requiresIdempotencyKey = true,
            requiresConfirmationToken = true
    )
    public AfterServiceResponse execute(IdempotencyKey idempotencyKey, AfterServiceRequest req) {
        Objects.requireNonNull(req, "req");

        // 幂等检查 — 文章: '即使模型重复调用，系统也应该返回第一次的执行结果'
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            String existingId = (String) put.value();
            if (existingId != null) {
                return new AfterServiceResponse(existingId, req.actionType(), List.of("幂等回放"), true);
            }
        }

        List<String> steps = new ArrayList<>();
        String auditId = "AUD-" + java.util.UUID.randomUUID().toString().substring(0, 8);

        switch (req.actionType()) {
            case "REFUND_CONFIRMED" -> {
                steps.add("退款已确认: orderId=" + req.orderId() + ", amount=" + req.amountCents() + " cents");
                steps.add("审计记录已写入: auditId=" + auditId);
                // 发通知
                sendNotification(req.tenantId(), req.userId(), "REFUND_CREATED",
                        "您的退款申请已确认，金额 " + (req.amountCents() / 100.0) + " 元，预计 3-5 个工作日到账");
                steps.add("用户通知已发送: template=REFUND_CREATED");
            }
            case "CANCEL_CONFIRMED" -> {
                steps.add("订单取消已确认: orderId=" + req.orderId());
                steps.add("审计记录已写入: auditId=" + auditId);
                sendNotification(req.tenantId(), req.userId(), "ORDER_CANCELLED",
                        "您的订单 " + req.orderId() + " 已成功取消");
                steps.add("用户通知已发送: template=ORDER_CANCELLED");
            }
            case "COMPLAINT_ESCALATED" -> {
                steps.add("投诉已升级: orderId=" + req.orderId() + ", escalationReason=" + req.escalationReason());
                steps.add("审计记录已写入: auditId=" + auditId);
                sendNotification(req.tenantId(), req.userId(), "HUMAN_HANDOFF",
                        "您的投诉已升级处理，专属客服将尽快联系您");
                steps.add("用户通知已发送: template=HUMAN_HANDOFF");
            }
            default -> throw new IllegalArgumentException(
                    "Unknown actionType: " + req.actionType()
                            + " (expected: REFUND_CONFIRMED / CANCEL_CONFIRMED / COMPLAINT_ESCALATED)");
        }

        // 写审计
        var auditRecord = new AfterServiceAuditPort.AuditRecord(
                auditId, req.orderId(), req.actionType(), steps, true, System.currentTimeMillis());
        auditRepo.save(auditRecord);

        // 回填幂等结果
        idempotencyStore.replace(idempotencyKey, auditId);
        return new AfterServiceResponse(auditId, req.actionType(), steps, true);
    }

    private void sendNotification(String tenantId, String userId, String template, String content) {
        var record = new NotificationRepositoryPort.NotificationRecord(
                NotificationRepositoryPort.newNotificationId(),
                tenantId, userId, template, content, java.time.Instant.now());
        notificationRepo.save(record);
    }

    public record AfterServiceRequest(
            String tenantId,
            String userId,
            String orderId,
            String actionType,
            long amountCents,
            String escalationReason
    ) {}

    public record AfterServiceResponse(
            String auditId,
            String actionType,
            List<String> steps,
            boolean success
    ) {}
}
