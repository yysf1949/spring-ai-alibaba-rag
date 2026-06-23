package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.core.exception.KbVersionNotFoundException;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Phase 18 P2 — KB version lifecycle agent tool.
 *
 * <h2>Why L2_WRITE (not L1_READ)</h2>
 * <p>Exposes both read-only operations ({@code listVersions}, {@code getActiveVersion})
 * AND write operations ({@code switch}, {@code rollback}). Writes change the active
 * pointer of a KB and immediately affect what every subsequent {@code kb_search}
 * call will see — that's a reversible-but-still-business-state operation,
 * which puts the tool at L2. The StageAwareToolAuthorizer will block it on
 * Stage 1 (only L1 visible) and allow it on Stage 2+.</p>
 *
 * <h2>Why a separate tool from {@code kb_search}</h2>
 * <p>The LLM doesn't have to call {@code kb_version_*} before every retrieval —
 * it only needs to call them when (a) the user explicitly asks "which version
 * is live?" or "switch back to v2", or (b) the LLM wants to confirm a
 * specific version returned by {@code kb_search} chunks. Keeping them
 * separate makes the read/write distinction visible in the tool list.</p>
 *
 * <h2>Action encoding</h2>
 * <p>Single record {@link KbVersionRequest} with an {@code action} enum
 * ({@code list}, {@code getActive}, {@code switch}, {@code rollback}). Top-level
 * record — same defensive shape as {@link KbSearchTool}'s top-level records
 * (avoids the P0 record-inner-class Spring AI 1.0.9 bug).</p>
 */
@Component
@ConditionalOnBean(KbVersionService.class)
public class KbVersionTool {

    private final KbVersionService kbVersionService;

    public KbVersionTool(KbVersionService kbVersionService) {
        this.kbVersionService = Objects.requireNonNull(kbVersionService, "kbVersionService");
    }

    @ToolSpec(
            name = "kb_version",
            description = "管理知识库版本。action:list/get_active/switch/rollback。返回action/versions/status/activeVersion。"
                    + "用户问'知识库现在是什么版本'、'切回上一个版本'。KB级操作影响所有检索。",
            riskLevel = RiskLevel.L2_REVERSIBLE,
            idempotent = true,
            requiresIdempotencyKey = true)
    public KbVersionResponse manage(KbVersionRequest request) {
        Objects.requireNonNull(request, "request");
        return switch (request.action()) {
            case LIST -> new KbVersionResponse(
                    KbVersionAction.LIST,
                    kbVersionService.listVersions(request.tenantId(), request.kbId()),
                    "OK",
                    null);
            case GET_ACTIVE -> {
                Optional<Long> active = kbVersionService.getActiveVersion(
                        request.tenantId(), request.kbId());
                yield new KbVersionResponse(
                        KbVersionAction.GET_ACTIVE,
                        List.of(),
                        active.map(v -> "active version = " + v).orElse("no active version"),
                        active.orElse(null));
            }
            case SWITCH -> {
                requireVersionId(request);
                long target = request.versionId();
                kbVersionService.publish(request.tenantId(), request.kbId(), target);
                yield new KbVersionResponse(
                        KbVersionAction.SWITCH,
                        List.of(),
                        "switched to version " + target,
                        target);
            }
            case ROLLBACK -> {
                requireVersionId(request);
                long target = request.versionId();
                kbVersionService.rollback(request.tenantId(), request.kbId(), target);
                yield new KbVersionResponse(
                        KbVersionAction.ROLLBACK,
                        List.of(),
                        "rolled back to version " + target,
                        target);
            }
        };
    }

    private static void requireVersionId(KbVersionRequest request) {
        if (request.versionId() == null) {
            throw new IllegalArgumentException(
                    "action " + request.action() + " requires versionId");
        }
    }
}