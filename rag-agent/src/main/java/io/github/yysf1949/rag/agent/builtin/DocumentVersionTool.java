package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 19 — document-level version lifecycle agent tool.
 *
 * <h2>Why L2_WRITE (not L1_READ)</h2>
 * <p>Same reasoning as {@link KbVersionTool}: exposing both read
 * ({@code LIST}, {@code GET_ACTIVE}) and write ({@code PUBLISH},
 * {@code ROLLBACK}) operations. Writes change the active pointer of a doc,
 * which immediately affects what every subsequent {@code kb_search} call
 * will see for that doc.</p>
 *
 * <h2>Why a separate tool from {@code kb_version}</h2>
 * <p>Document-level versions are orthogonal to KB-level versions: a doc can
 * be rolled back independently of the rest of the KB. Mixing them in one
 * tool would mean forcing the LLM to think about cardinality it doesn't
 * care about. Separate tools = separate intents.</p>
 */
@Component
@ConditionalOnBean(DocumentVersionService.class)
public class DocumentVersionTool {

    private final DocumentVersionService documentVersionService;

    public DocumentVersionTool(DocumentVersionService documentVersionService) {
        this.documentVersionService = Objects.requireNonNull(documentVersionService, "documentVersionService");
    }

    @ToolSpec(
            name = "doc_version",
            description = "管理文档版本。action:list/get_active/publish/rollback。返回action/versions/status/activeVersion。"
                    + "用户问'这个文档有哪些版本'、'帮我回滚到上个版本'。文档级操作不影响其他文档。",
            riskLevel = RiskLevel.L2_REVERSIBLE,
            idempotent = true)
    public DocumentVersionResponse manage(DocumentVersionRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (request.action()) {
            case LIST -> new DocumentVersionResponse(
                    DocumentVersionAction.LIST,
                    documentVersionService.listVersions(request.tenantId(), request.kbId(), request.docId()),
                    "OK",
                    null);
            case GET_ACTIVE -> {
                Optional<Long> active = documentVersionService.getActiveVersion(
                        request.tenantId(), request.kbId(), request.docId());
                yield new DocumentVersionResponse(
                        DocumentVersionAction.GET_ACTIVE,
                        List.of(),
                        active.map(v -> "active version = " + v).orElse("no active version"),
                        active.orElse(null));
            }
            case PUBLISH -> {
                long target = requireVersionId(request);
                DocumentVersionMeta meta = documentVersionService.publish(
                        request.tenantId(), request.kbId(), request.docId(),
                        target, request.sourceLabel());
                yield new DocumentVersionResponse(
                        DocumentVersionAction.PUBLISH,
                        List.of(meta),
                        "published version " + target,
                        target);
            }
            case ROLLBACK -> {
                long target = requireVersionId(request);
                DocumentVersionMeta meta = documentVersionService.rollback(
                        request.tenantId(), request.kbId(), request.docId(), target);
                yield new DocumentVersionResponse(
                        DocumentVersionAction.ROLLBACK,
                        List.of(meta),
                        "rolled back to version " + target,
                        target);
            }
        };
    }

    private static long requireVersionId(DocumentVersionRequest request) {
        if (request.versionId() == null) {
            throw new IllegalArgumentException(
                    "action " + request.action() + " requires versionId");
        }
        return request.versionId();
    }
}
