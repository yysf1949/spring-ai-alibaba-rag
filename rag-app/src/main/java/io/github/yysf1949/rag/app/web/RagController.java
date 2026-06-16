package io.github.yysf1949.rag.app.web;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.github.yysf1949.rag.app.config.MdcTenantFilter;
import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.KbVersion;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.QAService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * REST surface for the online QA chain — design spec §13.12.
 *
 * <h2>Endpoint</h2>
 * <pre>
 *   POST /api/qa
 *   Headers: X-Tenant-Id (required, authoritative)
 *            X-Request-Id (optional; auto-generated if absent)
 *   Body:    { userId, sessionId?, rawText, permissionTags?, topK?, kbVersion? }
 * </pre>
 *
 * <h2>Tenant resolution</h2>
 * The {@code tenantId} in the body is <b>ignored</b>. The HTTP header
 * {@code X-Tenant-Id} is authoritative (set by the API gateway or the
 * auth filter). See MULTI_TENANT.md §8 — never trust the body.
 *
 * <h2>Error model</h2>
 * Validation errors → 400. Vector store / embedding gateway down → 503
 * with {@code Retry-After: 30} (the QAService propagates the exception;
 * the {@code @ExceptionHandler} in {@link RagExceptionHandler} does the
 * translation). Everything else → 200 with an {@link Answer} object
 * whose {@code source} field records which leg produced the answer
 * ({@code CACHE | LLM | FALLBACK_RULE}).
 */
@RestController
@RequestMapping("/api")
public class RagController {

    private final QAService qaService;

    public RagController(QAService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/qa")
    public ResponseEntity<Answer> qa(
            HttpServletRequest request,
            @Valid @RequestBody QaRequest body) {

        String tenantId = requiredTenant(request);
        Set<String> tags = body.permissionTags == null
                ? Set.of()
                : new HashSet<>(body.permissionTags);
        KbVersion kbVersion = body.kbVersion == null
                ? null
                : new KbVersion(tenantId, body.kbVersion.kbId, body.kbVersion.version);

        Query q = new Query(
                tenantId,
                body.userId,
                body.sessionId,
                body.rawText,
                tags,
                body.topK == null ? 0 : body.topK,
                kbVersion);

        Answer answer = qaService.answer(q);
        return ResponseEntity.status(HttpStatus.OK).body(answer);
    }

    private static String requiredTenant(HttpServletRequest request) {
        String tenant = request.getHeader(MdcTenantFilter.HEADER_TENANT);
        if (tenant == null || tenant.isBlank()) {
            throw new MissingTenantException(
                    "Missing " + MdcTenantFilter.HEADER_TENANT + " header. "
                            + "All /api/* requests must be authenticated at the gateway.");
        }
        return tenant;
    }

    // ─── request / response DTOs ──────────────────────────────────────────

    /**
     * Public DTO for the {@code POST /api/qa} body. {@code tenantId} is
     * intentionally absent — see class javadoc on tenant resolution.
     */
    public static class QaRequest {
        @NotBlank
        public String userId;
        public String sessionId;
        @NotBlank
        public String rawText;
        public List<String> permissionTags;
        public Integer topK;
        public KbVersionDto kbVersion;
    }

    public static class KbVersionDto {
        @NotBlank
        public String kbId;
        @NotNull
        public Long version;
    }

    /** Thrown when the gateway didn't pass a tenant header. */
    public static class MissingTenantException extends RuntimeException {
        public MissingTenantException(String msg) { super(msg); }
    }
}
