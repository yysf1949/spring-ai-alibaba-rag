package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.Thumb;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Phase 40 T1 — 用户反馈 REST API (R10: Active Learning 反馈闭环第一步).
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code POST /api/agent/feedback} — 提交反馈 (thumb/rating/comment 三选一或多选)</li>
 *   <li>{@code GET /api/agent/feedback} — 列出当前 tenant 的反馈 (分页, 给 Admin UI 用)</li>
 *   <li>{@code GET /api/agent/feedback/{feedbackId}} — 单条反馈详情</li>
 *   <li>{@code GET /api/agent/feedback/conversation/{conversationId}} — 按会话查询</li>
 * </ul>
 *
 * <h2>租户隔离</h2>
 * <p>tenantId 走 {@code X-Tenant-Id} header (跟 {@code RagController}/{@code HandoffTicketController} 风格一致).
 * 跨租户访问返回 404, 严守硬墙.</p>
 */
@RestController
@RequestMapping("/api/agent/feedback")
@Tag(name = "Feedback", description = "Phase 40: 用户反馈收集 API (R10).")
public class FeedbackController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackController.class);

    private final FeedbackPort feedbackPort;

    public FeedbackController(FeedbackPort feedbackPort) {
        this.feedbackPort = feedbackPort;
    }

    /**
     * 提交反馈 — 必须给出 thumb / rating / comment 至少一个。
     */
    @PostMapping
    @Operation(summary = "提交用户反馈 (T1)",
            description = "thumb (UP/DOWN/null) + rating (1-5, optional) + comment (text, optional). "
                    + "至少一个字段非空，否则 400.")
    public ResponseEntity<FeedbackRecord> submitFeedback(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody FeedbackRequest req) {

        if (req.thumb() == null && req.rating() == null
                && (req.comment() == null || req.comment().isBlank())) {
            throw new FeedbackValidationException(
                    "At least one of thumb/rating/comment must be provided");
        }

        FeedbackRecord record = new FeedbackRecord(
                FeedbackRecord.newFeedbackId(),
                tenantId,
                req.userId(),
                req.conversationId(),
                req.messageId(),
                req.thumb(),
                req.rating(),
                req.comment(),
                req.sourceChannel() == null ? "api" : req.sourceChannel(),
                req.kbVersion(),
                Instant.now().toEpochMilli()
        );
        FeedbackRecord saved = feedbackPort.save(record);
        log.info("Feedback saved: feedbackId={} tenantId={} userId={} conv={} thumb={} rating={}",
                saved.feedbackId(), saved.tenantId(), saved.userId(),
                saved.conversationId(), saved.thumb(), saved.rating());
        return ResponseEntity.ok(saved);
    }

    /**
     * 按 tenant 列反馈 — 给 Admin UI 用, 分页.
     */
    @GetMapping
    @Operation(summary = "列出当前 tenant 的反馈")
    public ResponseEntity<List<FeedbackRecord>> listFeedback(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestParam(required = false, defaultValue = "100") @Min(1) @Max(1000) int limit) {
        return ResponseEntity.ok(feedbackPort.findByTenant(tenantId, limit));
    }

    /**
     * 单条反馈详情 — 跨租户访问返回 404 (严守硬墙).
     */
    @GetMapping("/{feedbackId}")
    @Operation(summary = "单条反馈详情")
    public ResponseEntity<FeedbackRecord> getFeedback(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String feedbackId) {
        Optional<FeedbackRecord> record = feedbackPort.findById(tenantId, feedbackId);
        return record.map(ResponseEntity::ok).orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 按会话 ID 查询 — 给前端 "本次会话的反馈" 视图用.
     */
    @GetMapping("/conversation/{conversationId}")
    @Operation(summary = "按会话 ID 查询反馈")
    public ResponseEntity<List<FeedbackRecord>> listByConversation(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String conversationId) {
        return ResponseEntity.ok(feedbackPort.findByConversation(tenantId, conversationId));
    }

    /**
     * 反馈请求 DTO。
     *
     * @param userId         终端用户 ID
     * @param conversationId 会话 ID (必填)
     * @param messageId      关联消息 ID (👍/👎 时填, 纯文字可空)
     * @param thumb          反馈方向 (UP/DOWN/null)
     * @param rating         1-5 评分 (可空)
     * @param comment        文字反馈 (可空, 最长 2048)
     * @param sourceChannel  来源渠道 (web/wechat/email/api), 默认 api
     * @param kbVersion      关联 KB 版本 (可空)
     */
    public record FeedbackRequest(
            @NotBlank String userId,
            @NotBlank String conversationId,
            String messageId,
            Thumb thumb,
            @Min(1) @Max(5) Integer rating,
            @Size(max = 2048) String comment,
            String sourceChannel,
            String kbVersion
    ) { }
}