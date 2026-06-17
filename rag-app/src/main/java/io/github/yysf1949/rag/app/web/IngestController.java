package io.github.yysf1949.rag.app.web;

import io.github.yysf1949.rag.app.config.MdcTenantFilter;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.port.IngestService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Set;

/**
 * REST surface for the asynchronous ingest pipeline — design spec §6.3.
 *
 * <h2>Endpoints</h2>
 * <pre>
 *   POST /api/ingest                  →  202 + PENDING job
 *   GET  /api/ingest/{jobId}          →  200 + job snapshot
 *   POST /api/ingest/{jobId}/publish  →  200 + PUBLISHED job
 * </pre>
 *
 * <h2>Headers</h2>
 * <ul>
 *   <li>{@code X-Tenant-Id} — required on every call, authoritative (mirrors
 *       the {@code /api/qa} contract; see {@link RagController}).</li>
 * </ul>
 *
 * <h2>Tenant resolution</h2>
 * The HTTP header is authoritative. The body's {@code tenantId} field is
 * accepted for symmetry with the {@code /api/qa} request shape but is
 * <b>ignored</b>.
 *
 * <h2>Async semantics</h2>
 * {@code POST /api/ingest} returns {@code 202 Accepted} immediately with the
 * freshly-created job in {@code PENDING} state. The caller polls
 * {@code GET /api/ingest/{jobId}} until the job reaches {@code READY} or
 * {@code FAILED}, then atomically promotes it via
 * {@code POST /api/ingest/{jobId}/publish}.
 */
@RestController
@RequestMapping("/api/ingest")
@Tag(name = "Ingest", description = "Asynchronous document ingest endpoints.")
public class IngestController {

    private final IngestService ingestService;

    public IngestController(IngestService ingestService) {
        this.ingestService = ingestService;
    }

    @PostMapping
    @Operation(
            summary = "Submit a document for asynchronous ingest.",
            description = "Splits, embeds, and writes the document to the staging index. "
                    + "Returns 202 Accepted with the freshly-created PENDING job — the caller "
                    + "polls /api/ingest/{jobId} until READY, then calls /publish to atomically "
                    + "flip the active index (spec §6.3).")
    @ApiResponses({
            @ApiResponse(responseCode = "202", description = "Job accepted, processing in background."),
            @ApiResponse(responseCode = "400", description = "Validation failure (missing kbId / documentId / sections).",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
            @ApiResponse(responseCode = "401", description = "Missing or blank X-Tenant-Id header.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public ResponseEntity<IngestJob> submit(
            @RequestHeader(value = MdcTenantFilter.HEADER_TENANT, required = false) String tenantHeader,
            HttpServletRequest request,
            @Valid @RequestBody IngestRequest body) {
        String tenant = requiredTenant(request, tenantHeader);
        Document doc = new Document(
                tenant,
                body.kbId,
                body.documentId,
                String.valueOf(body.documentVersion),
                body.title,
                body.sourceUri,
                body.permissionTags == null ? Set.of() : Set.copyOf(body.permissionTags),
                body.sections == null ? List.of()
                        : body.sections.stream()
                                .map(s -> new Document.Section(
                                        resolveHeading(s),
                                        s.content == null ? "" : s.content))
                                .toList());
        IngestJob job = ingestService.ingestAsync(doc);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
    }

    @GetMapping("/{jobId}")
    @Operation(
            summary = "Fetch the current snapshot of an ingest job.",
            description = "Returns the job's current status, chunk counters, and error message (if FAILED). "
                    + "Returns 404 if the jobId is unknown or has been reaped by the TTL sweeper.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job snapshot returned."),
            @ApiResponse(responseCode = "404", description = "jobId unknown or evicted by the 24h TTL.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public IngestJob get(HttpServletRequest request, @PathVariable String jobId) {
        requiredTenant(request, request.getHeader(MdcTenantFilter.HEADER_TENANT));
        return ingestService.getJob(jobId).orElseThrow(() ->
                new IngestJobNotFoundException("Unknown jobId=" + jobId));
    }

    @PostMapping("/{jobId}/publish")
    @Operation(
            summary = "Promote a READY job to PUBLISHED (atomic active-index flip).",
            description = "Performs spec §6.1's atomic index switch. The job must be in READY state; "
                    + "any other state results in a FAILED terminal state with an explanatory error message.")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Job promoted to PUBLISHED."),
            @ApiResponse(responseCode = "404", description = "jobId unknown or evicted.",
                    content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
    })
    public IngestJob publish(HttpServletRequest request, @PathVariable String jobId) {
        requiredTenant(request, request.getHeader(MdcTenantFilter.HEADER_TENANT));
        return ingestService.publish(jobId);
    }

    /**
     * Pick the non-null breadcrumb path from an {@link IngestSection}, in
     * preference order {@code heading → path → ""}. The {@code path} alias
     * exists because the demo-refund-qa.sh script and several internal
     * tools wire that name; see IngestSection's docstring.
     */
    private static String resolveHeading(IngestSection s) {
        if (s.heading != null && !s.heading.isEmpty()) return s.heading;
        if (s.path != null && !s.path.isEmpty()) return s.path;
        return "";
    }

    /**
     * Resolve and validate the tenant header. Mirrors the pattern in
     * {@link RagController#requiredTenant(HttpServletRequest)} — Spring's
     * {@code @RequestHeader(required = true)} returns 400
     * {@code MissingServletRequestHeaderException} by default, but spec §6
     * requires a 401 {@code ProblemDetail} (matching the
     * {@code /api/qa} error model).
     */
    private static String requiredTenant(HttpServletRequest request, String header) {
        if (header == null) {
            header = request.getHeader(MdcTenantFilter.HEADER_TENANT);
        }
        if (header == null || header.isBlank()) {
            throw new RagController.MissingTenantException(
                    "Missing " + MdcTenantFilter.HEADER_TENANT + " header. "
                            + "All /api/* requests must be authenticated at the gateway.");
        }
        return header;
    }

    // ─── request shape ─────────────────────────────────────────────────────

    /**
     * Ingest request body — mirrors the {@code /api/qa} request shape for
     * symmetry. The body's {@code tenantId} is ignored; the
     * {@code X-Tenant-Id} header is authoritative.
     */
    public static class IngestRequest {
        @SuppressWarnings("unused")
        public String tenantId;            // echo of header; ignored server-side
        @NotBlank public String kbId;
        @NotBlank public String documentId;
        @NotNull  public Long documentVersion;
        @NotBlank public String title;
        @NotBlank public String sourceUri;
        public List<String> permissionTags;
        @NotEmpty public List<IngestSection> sections;
    }

    /**
     * Section body shape. Mapped to {@link Document.Section} which uses
     * {@code heading/body} field names; we expose {@code content} on the
     * wire to match conventional HTTP document-ingest APIs (PDF/Word
     * parsers produce "content" more often than "body").
     */
    public static class IngestSection {
        // Canonical wire field is `heading` (matches Document.Section.heading
        // and the IngestSection docstring). Many internal tools — including
        // demo-refund-qa.sh — send `path` instead. We accept both: Jackson
        // populates whichever it sees, and the controller's mapping loop
        // falls back to whichever is non-null when building the Document.
        // The extra `path` field is also marked @JsonIgnoreProperties-aware
        // so unknown fields don't trigger a 400 (PathVariable-style
        // strictness would otherwise reject `path` entirely).
        public String heading;             // breadcrumb path; optional
        public String path;                // alias for `heading`; optional
        @NotBlank public String content;   // raw text
    }

    // ─── exceptions ────────────────────────────────────────────────────────

    /**
     * Thrown when a caller asks for a jobId that the repository has never
     * seen — typically a stale client polling an evicted entry, or a typo
     * in a CI script. Translated to HTTP 404 by
     * {@link RagExceptionHandler}.
     */
    public static class IngestJobNotFoundException extends RuntimeException {
        public IngestJobNotFoundException(String message) {
            super(message);
        }
    }
}
