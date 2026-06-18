package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Optional;

/**
 * Phase 18 P2 — REST surface for {@link KbVersionService}.
 *
 * <h2>Why a separate controller (not extend {@code RagController})</h2>
 * <p>{@code RagController} is in {@code rag-app} and bound to the
 * RAG HTTP API contract; agent tool endpoints live in {@code rag-agent}.
 * The KB version lifecycle is owned by the agent action layer because the
 * {@code KbVersionTool} (LLM-facing) shares the same {@link KbVersionService}
 * — the REST endpoint is just another client of that service.</p>
 *
 * <h2>Endpoints</h2>
 * <ul>
 *   <li>{@code GET  /api/agent/kb-versions/{kbId}} — list all versions</li>
 *   <li>{@code GET  /api/agent/kb-versions/{kbId}/active} — get active version id</li>
 *   <li>{@code POST /api/agent/kb-versions/{kbId}/publish} — publish a version</li>
 * </ul>
 *
 * <h2>Tenant isolation</h2>
 * <p>Like the other agent endpoints, {@code tenantId} comes from the
 * {@code X-Tenant-Id} header — never from the request body. The path
 * variable {@code kbId} is namespaced under that tenant.</p>
 */
@RestController
@RequestMapping("/api/agent/kb-versions")
@Tag(name = "KB Versions", description = "Knowledge base version lifecycle (Phase 18 P2).")
@ConditionalOnBean(KbVersionService.class)
public class KbVersionController {

    private final KbVersionService kbVersionService;

    public KbVersionController(KbVersionService kbVersionService) {
        this.kbVersionService = kbVersionService;
    }

    public record ListResponse(@NotBlank String kbId, List<KbVersionMeta> versions) {}
    public record ActiveResponse(@NotBlank String kbId, Long activeVersionId) {}
    public record PublishRequest(@NotNull Long versionId) {}
    public record PublishResponse(@NotBlank String kbId, long versionId, String status) {}

    @GetMapping("/{kbId}")
    @Operation(summary = "列出 KB 所有版本 (新→旧)",
            description = "包含 DRAFT / STAGING / ACTIVE / DEPRECATED 全部状态. tenantId 走 X-Tenant-Id header.")
    public ResponseEntity<ListResponse> list(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String kbId) {
        List<KbVersionMeta> versions = kbVersionService.listVersions(tenantId, kbId);
        return ResponseEntity.ok(new ListResponse(kbId, versions));
    }

    @GetMapping("/{kbId}/active")
    @Operation(summary = "查当前生效版本 (active version id)",
            description = "KB 从未发布时返回 200 + activeVersionId=null (区别于 404).")
    public ResponseEntity<ActiveResponse> active(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String kbId) {
        Optional<Long> active = kbVersionService.getActiveVersion(tenantId, kbId);
        return ResponseEntity.ok(new ActiveResponse(kbId, active.orElse(null)));
    }

    @PostMapping("/{kbId}/publish")
    @Operation(summary = "发布指定版本 (publish / 隐含 rollback)",
            description = "将 versionId 设为 KB 当前 active 版本. 已 active 时 idempotent no-op. "
                    + "之前的 active 自动变 DEPRECATED. 等价于 rollback 当 versionId 是历史版本时.")
    public ResponseEntity<PublishResponse> publish(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String kbId,
            @Valid @RequestBody PublishRequest req) {
        kbVersionService.publish(tenantId, kbId, req.versionId());
        return ResponseEntity.ok(new PublishResponse(kbId, req.versionId(), "ACTIVE"));
    }
}