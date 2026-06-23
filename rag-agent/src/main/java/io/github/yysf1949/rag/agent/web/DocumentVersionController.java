package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
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
 * Phase 19 — REST surface for {@link DocumentVersionService}.
 *
 * <h2>Why a separate controller (not nested under KB versions)</h2>
 * <p>Document-level versions are an orthogonal dimension from KB-level
 * versions. They live in separate stores (Phase 19 ships them that way).
 * Mixing them under the same {@code /api/agent/kb-versions} path would
 * confuse API consumers about cardinality. Clean URL hierarchy:
 * <pre>
 *   GET  /api/agent/kb-versions/{kbId}/docs/{docId}
 *   GET  /api/agent/kb-versions/{kbId}/docs/{docId}/active
 *   POST /api/agent/kb-versions/{kbId}/docs/{docId}/publish
 * </pre>
 * </p>
 *
 * <h2>Tenant isolation</h2>
 * <p>Like the other agent endpoints, {@code tenantId} comes from the
 * {@code X-Tenant-Id} header — never from the request body. The path
 * variables {@code kbId} and {@code docId} are namespaced under that tenant.</p>
 */
@RestController
@RequestMapping("/api/agent/kb-versions/{kbId}/docs")
@Tag(name = "Document Versions", description = "Document-level version lifecycle (Phase 19).")
@ConditionalOnBean(DocumentVersionService.class)
public class DocumentVersionController {

    private final DocumentVersionService documentVersionService;

    public DocumentVersionController(DocumentVersionService documentVersionService) {
        this.documentVersionService = documentVersionService;
    }

    public record ListResponse(@NotBlank String kbId, @NotBlank String docId,
                               List<DocumentVersionMeta> versions) {}
    public record ActiveResponse(@NotBlank String kbId, @NotBlank String docId,
                                 Long activeVersionId) {}
    public record PublishRequest(@NotNull Long versionId, String sourceLabel) {}
    public record PublishResponse(@NotBlank String kbId, @NotBlank String docId,
                                  long versionId, String status) {}

    @GetMapping("/{docId}")
    @Operation(summary = "列出文档所有版本 (新→旧)",
            description = "包含 DRAFT / ACTIVE / DEPRECATED 全部状态. tenantId 走 X-Tenant-Id header.")
    public ResponseEntity<ListResponse> list(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String kbId,
            @PathVariable @NotBlank String docId) {
        List<DocumentVersionMeta> versions = documentVersionService.listVersions(tenantId, kbId, docId);
        return ResponseEntity.ok(new ListResponse(kbId, docId, versions));
    }

    @GetMapping("/{docId}/active")
    @Operation(summary = "查文档当前生效版本 (active version id)",
            description = "文档从未发布时返回 200 + activeVersionId=null (区别于 404).")
    public ResponseEntity<ActiveResponse> active(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String kbId,
            @PathVariable @NotBlank String docId) {
        Optional<Long> active = documentVersionService.getActiveVersion(tenantId, kbId, docId);
        return ResponseEntity.ok(new ActiveResponse(kbId, docId, active.orElse(null)));
    }

    @PostMapping("/{docId}/publish")
    @Operation(summary = "发布指定版本 (publish / 隐含 rollback)",
            description = "将 versionId 设为 doc 当前 active 版本. 已 active 时 idempotent no-op. "
                    + "之前的 active 自动变 DEPRECATED. 等价于 rollback 当 versionId 是历史版本时.")
    public ResponseEntity<PublishResponse> publish(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @PathVariable @NotBlank String kbId,
            @PathVariable @NotBlank String docId,
            @Valid @RequestBody PublishRequest req) {
        documentVersionService.publish(tenantId, kbId, docId, req.versionId(), req.sourceLabel());
        return ResponseEntity.ok(new PublishResponse(kbId, docId, req.versionId(), "ACTIVE"));
    }
}
