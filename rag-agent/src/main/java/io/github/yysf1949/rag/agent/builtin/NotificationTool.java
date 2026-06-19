package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.NotificationRepositoryPort;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * 站内通知工具 — L2 可逆 (发错可重发或撤回, 不涉及资金/订单态修改)。
 *
 * <h2>对齐「路条编程」文章 §2 能力清单 "发送短信/站内通知"</h2>
 * <p>原话: AI 客服做完事需要"通知用户" — 退款创建/优惠券补发/订单取消/
 * 工单创建/转人工 5 大场景都需要用户感知。此工具统一通知出口。</p>
 *
 * <h2>安全护栏 (Phase 14 mock)</h2>
 * <ul>
 *   <li>模板白名单: 5 个固定模板 (REFUND_CREATED/COUPON_ISSUED/ORDER_CANCELLED/
 *       TICKET_CREATED/HUMAN_HANDOFF) — 防 Agent 自由发挥</li>
 *   <li>长度限制: 500 字符 — 防滥用</li>
 *   <li>5 分钟去重: 同 user + 同 template 在 5 分钟内只发 1 次 (返回已存在项, 幂等)</li>
 *   <li>requiresIdempotencyKey=true: 防网络重试导致重复发送</li>
 * </ul>
 *
 * <h2>生产替换 (Phase 15+)</h2>
 * <p>5 分钟去重换 Redis SET + EXPIRE; 真实发送走消息网关 SDK (短信/邮件/推送)。</p>
 */
@Component
public class NotificationTool {

    /** 5 个固定模板 (白名单) */
    public static final Set<String> ALLOWED_TEMPLATES = Set.of(
            "REFUND_CREATED", "COUPON_ISSUED", "ORDER_CANCELLED",
            "TICKET_CREATED", "HUMAN_HANDOFF");

    /** 单条内容字符上限 */
    public static final int MAX_CONTENT_LENGTH = 500;

    /** 同 user + 同 template 去重窗口 */
    public static final Duration DEDUPE_WINDOW = Duration.ofMinutes(5);

    private final NotificationRepositoryPort repo;

    public NotificationTool(NotificationRepositoryPort repo) {
        this.repo = Objects.requireNonNull(repo, "repo");
    }

    @ToolSpec(
            name = "send_notification",
            description = "发送站内通知。5种模板:REFUND_CREATED(退款)/COUPON_ISSUED(优惠券)/ORDER_CANCELLED(取消)/TICKET_CREATED(工单)/HUMAN_HANDOFF(转人工)。返回notificationId/status/sentAt。用户说'退款成功了通知我'。500字上限+5分钟去重。",
            riskLevel = RiskLevel.L2_REVERSIBLE,
            idempotent = true,
            requiresIdempotencyKey = true
    )
    public NotificationResponse send(SendNotificationRequest req) {
        Objects.requireNonNull(req, "req");

        // 1. 模板白名单
        if (!ALLOWED_TEMPLATES.contains(req.template())) {
            throw new IllegalArgumentException(
                    "Template not whitelisted: " + req.template() +
                    " (allowed: " + ALLOWED_TEMPLATES + ")");
        }

        // 2. 长度限制
        if (req.content() == null || req.content().length() > MAX_CONTENT_LENGTH) {
            throw new IllegalArgumentException(
                    "Content exceeds " + MAX_CONTENT_LENGTH + " chars or is null");
        }

        // 3. 5 分钟去重窗口 — 命中则返回已存在项 (幂等)
        if (repo.existsByUserAndTemplateWithinWindow(
                req.userId(), req.template(), DEDUPE_WINDOW)) {
            var existing = repo.findRecentByUserAndTemplate(
                    req.userId(), req.template())
                    .orElseThrow();  // existsBy... 刚 true, 不可能空
            return new NotificationResponse(
                    existing.notificationId(), "DEDUPED", existing.sentAt().toString());
        }

        // 4. 写入新通知
        var record = new NotificationRepositoryPort.NotificationRecord(
                NotificationRepositoryPort.newNotificationId(),
                req.tenantId(),
                req.userId(),
                req.template(),
                req.content(),
                Instant.now());
        repo.save(record);
        return new NotificationResponse(
                record.notificationId(), "SENT", record.sentAt().toString());
    }

    public record SendNotificationRequest(
            String tenantId,
            String userId,
            String template,
            String content,
            String idempotencyToken  // 备用字段,目前不直接校验(依赖 requiresIdempotencyKey=true 走 RiskGate)
    ) { }

    public record NotificationResponse(
            String notificationId,
            String status,        // SENT / DEDUPED
            String sentAt         // ISO-8601
    ) { }
}