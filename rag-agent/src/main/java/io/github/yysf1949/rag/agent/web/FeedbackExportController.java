package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Phase 40 T2 — 反馈 JSONL 导出 (R10: Active Learning 训练数据采集第二步).
 *
 * <h2>Endpoint</h2>
 * <pre>
 *   GET /api/admin/feedback/export
 *       ?tenantId=&lt;id&gt;
 *       &amp;from=&lt;epoch_ms | ISO-8601&gt;   (可选)
 *       &amp;to=&lt;epoch_ms | ISO-8601&gt;     (可选)
 *       &amp;format=jsonl                    (默认 jsonl, 当前仅支持)
 *       &amp;limit=&lt;1..100000&gt;              (默认 10000)
 *   Headers:
 *       X-Admin-Key: &lt;shared secret&gt;    (必填, 与 agent.admin.export-key 比对)
 * </pre>
 *
 * <h2>响应</h2>
 * <ul>
 *   <li>{@code Content-Type: application/x-ndjson} (Newline-Delimited JSON)</li>
 *   <li>{@code Content-Disposition: attachment; filename="feedback-{tenant}-{ts}.ndjson"}</li>
 *   <li>每行一条 JSON (UTF-8): feedback_id, tenant_id, user_id,
 *       conversation_id, message_id, thumb, rating, comment, kb_version,
 *       agent_identity, created_at</li>
 * </ul>
 *
 * <h2>鉴权</h2>
 * <p>{@code X-Admin-Key} 不匹配 / 缺失 → 403 Forbidden。key 来自
 * {@code agent.admin.export-key} (env {@code AGENT_ADMIN_EXPORT_KEY} 覆盖)。
 * 默认 dev key 是 {@code CHANGE-ME-dev-only} — 生产必须替换。</p>
 *
 * <h2>内存模型</h2>
 * <p>当前实现一次性把 {@code limit} 条记录 (默认 10000) 装进 List, 用
 * Jackson {@code writeValueAsString} 逐行写入 {@code StringBuilder}, 整体返回。
 * 单条 JSON &lt; 1KB → 10K records ≈ 10MB, 完全可控。</p>
 *
 * <p>{@code StreamingResponseBody} 留给 1M+ records 场景 — 届时改用
 * {@code JdbcTemplate.queryForStream} + 流式 Jackson {@code JsonGenerator}。
 * 当前返回 {@link ResponseEntity}{@code <String>} 走 Jackson 默认
 * {@code StringHttpMessageConverter} 完成 JSONL 编码, 避免在 MockMvc 环境下
 * 因 SRB 不被驱动导致响应体为空。</p>
 *
 * <h2>错误码</h2>
 * <ul>
 *   <li>403 — X-Admin-Key 缺失或错误</li>
 *   <li>500 — 导出参数错误 (时间反转 / 不支持的 format / 时间格式非法 /
 *       tenantId 缺失) 或存储读取失败, 由 {@link FeedbackExportException}
 *       → {@link AgentExceptionHandler} 映射</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/admin/feedback")
@Tag(name = "FeedbackAdmin", description = "Phase 40 T2: 反馈导出 (Active Learning 训练数据).")
public class FeedbackExportController {

    private static final Logger log = LoggerFactory.getLogger(FeedbackExportController.class);

    /** JSONL 媒体类型 (Newline-Delimited JSON). */
    public static final String MEDIA_NDJSON = "application/x-ndjson";

    private final FeedbackPort feedbackPort;
    private final ObjectMapper objectMapper;
    private final String adminKey;

    public FeedbackExportController(
            FeedbackPort feedbackPort,
            ObjectMapper objectMapper,
            @Value("${agent.admin.export-key:CHANGE-ME-dev-only}") String adminKey) {
        this.feedbackPort = feedbackPort;
        this.objectMapper = objectMapper;
        this.adminKey = adminKey == null ? "" : adminKey;
    }

    @GetMapping(value = "/export", produces = MEDIA_NDJSON)
    @Operation(summary = "导出反馈为 JSONL (Admin)",
            description = "返回 NDJSON 文本 — 每行一条反馈 JSON. "
                    + "需要 X-Admin-Key, 缺失或错误返回 403.")
    public ResponseEntity<String> exportFeedback(
            @RequestHeader(value = "X-Admin-Key", required = false) String providedKey,
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "from", required = false) String from,
            @RequestParam(value = "to", required = false) String to,
            @RequestParam(value = "format", required = false, defaultValue = "jsonl") String format,
            @RequestParam(value = "limit", required = false, defaultValue = "10000") int limit) {

        // 鉴权 — 在 SRB 触发前就 403, 避免空响应体
        if (providedKey == null || !constantTimeEquals(providedKey, adminKey)) {
            log.warn("Feedback export: X-Admin-Key missing or wrong, tenantId={}", tenantId);
            return ResponseEntity.status(HttpServletResponse.SC_FORBIDDEN).build();
        }
        if (tenantId == null || tenantId.isBlank()) {
            throw new FeedbackExportException("tenantId query param is required");
        }
        if (!"jsonl".equalsIgnoreCase(format)) {
            throw new FeedbackExportException(
                    "Unsupported format: " + format + " (only 'jsonl' supported)");
        }
        if (limit <= 0 || limit > 100_000) {
            throw new FeedbackExportException(
                    "limit must be in (0, 100000], got: " + limit);
        }

        Long fromMs = parseTimeMillis(from, "from");
        Long toMs = parseTimeMillis(to, "to");
        if (fromMs != null && toMs != null && fromMs > toMs) {
            throw new FeedbackExportException(
                    "from (" + fromMs + ") must be <= to (" + toMs + ")");
        }

        List<FeedbackRecord> records = feedbackPort.findByTenantRange(
                tenantId, fromMs, toMs, limit);
        log.info("Feedback export: tenantId={} from={} to={} → {} records",
                tenantId, fromMs, toMs, records.size());

        // 序列化: 逐条 JSON + \n → 整体 String. Jackson writeValueAsString
        // 对 LinkedHashMap 输出保持字段顺序.
        StringBuilder sb = new StringBuilder(records.size() * 256);
        for (FeedbackRecord r : records) {
            try {
                sb.append(objectMapper.writeValueAsString(toJsonlRow(r, adminKey)));
                sb.append('\n');
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new FeedbackExportException("Failed to serialize feedback " + r.feedbackId(), e);
            }
        }

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(MEDIA_NDJSON))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"feedback-" + sanitize(tenantId)
                                + "-" + System.currentTimeMillis() + ".ndjson\"")
                .body(sb.toString());
    }

    /**
     * 把 FeedbackRecord 序列化为 JSONL 行。字段命名走 snake_case 匹配微调
     * pipeline 的 pandas.read_json(lines=True) 习惯。
     */
    private Map<String, Object> toJsonlRow(FeedbackRecord r, String agentIdentity) {
        // agent_identity: dev 用 "rag-agent-v1" + key 前 8 字符后缀, 让多
        // 租户部署时能区分 pipeline 来源又不暴露完整 admin key.
        String identity = agentIdentity == null || agentIdentity.length() < 8
                ? "rag-agent-v1" : "rag-agent-v1:" + safeAgentTag(agentIdentity);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("feedback_id", r.feedbackId());
        m.put("tenant_id", r.tenantId());
        m.put("user_id", r.userId());
        m.put("conversation_id", r.conversationId());
        m.put("message_id", r.messageId());
        m.put("thumb", r.thumb() == null ? null : r.thumb().name());
        m.put("rating", r.rating());
        m.put("comment", r.comment());
        m.put("kb_version", r.kbVersion());
        m.put("agent_identity", identity);
        m.put("created_at", r.createdAt());
        return m;
    }

    /** 仅取 key 前 8 字符作为 tag, 避免暴露完整 key 进训练数据. */
    private static String safeAgentTag(String key) {
        return key.length() <= 8 ? key : key.substring(0, 8);
    }

    /**
     * 解析 from/to 参数 — 支持 epoch millis 字符串和 ISO-8601.
     * 返回 null 表示该端无界.
     */
    private static Long parseTimeMillis(String s, String paramName) {
        if (s == null || s.isBlank()) return null;
        try {
            return Long.parseLong(s.trim());
        } catch (NumberFormatException ignore) {
            // fall through to ISO-8601
        }
        try {
            return Instant.parse(s.trim()).toEpochMilli();
        } catch (DateTimeParseException e) {
            throw new FeedbackExportException(
                    paramName + " must be epoch millis or ISO-8601 instant, got: " + s);
        }
    }

    /** 防止 filename header 注入 — 只保留 [A-Za-z0-9._-]. */
    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    /**
     * 常量时间字符串比较 — 防止 timing attack 反推 adminKey.
     * 长度不等直接返回 false (不抛异常, 减少侧信道).
     */
    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        if (a.length() != b.length()) return false;
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}
