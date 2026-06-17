package io.github.yysf1949.rag.app.web;

import io.github.yysf1949.rag.app.audit.AuditChannel;
import io.github.yysf1949.rag.app.config.MdcTenantFilter;
import io.github.yysf1949.rag.core.model.AuditEvent;
import io.github.yysf1949.rag.core.model.Document;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.IngestService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
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

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final AuditChannel audit;
    private final MeterRegistry meterRegistry;

    public IngestController(
            IngestService ingestService,
            AuditChannel audit,
            MeterRegistry meterRegistry) {
        this.ingestService = ingestService;
        this.audit = audit;
        this.meterRegistry = meterRegistry;
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
        // C9.2 — start the request-level timer. We use a single timer
        // record(ingest_duration_ms) but with a tenant+kbId tag so the
        // dashboard can split by KB. We can't know the FINAL job status
        // synchronously (the heavy work runs on the executor), so we only
        // time the HTTP submission itself here; the per-job outcome is
        // counted via rag.ingest.documents.total (incremented in submit)
        // and rag.ingest.failures.total (incremented in the controller's
        // exception handler / 4xx path). The pipeline-layer timers in
        // IngestServiceImplMetricsTest remain the source of truth for
        // the actual split/embed/publish stages.
        Timer.Sample sample = Timer.start(meterRegistry);
        String requestId = MdcTenantFilter.requestId(request);
        try {
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
            // C9.2 — count the document submission, tagged by tenant + kbId
            // + initial status. Operators alert on a sudden drop in this
            // counter (a stuck ingest pipeline would manifest as no new
            // submissions even though HTTP is healthy).
            meterRegistry.counter(
                    "rag.ingest.documents.total",
                    Tags.of("tenant", tenant, "kbId", body.kbId,
                            "outcome", job.status().name()))
                    .increment();
            // Audit — KB_INGEST (admin action: a tenant just submitted a
            // document for processing). We record the documentId +
            // version + section count so an auditor can reconstruct the
            // exact submission later. The actorId is the X-User-Id
            // header (gateway-injected) — fall back to "anonymous" for
            // unauthenticated dev traffic.
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("documentId", body.documentId);
            fields.put("documentVersion", body.documentVersion);
            fields.put("sectionCount", body.sections == null ? 0 : body.sections.size());
            fields.put("permissionTags", body.permissionTags == null
                    ? List.of() : body.permissionTags);
            audit.record(AuditEvent.of(
                    AuditEvent.Type.KB_INGEST,
                    tenant,
                    request.getHeader("X-User-Id"),
                    requestId,
                    job.jobId(),
                    job.status() == IngestJobStatus.FAILED ? "FAILURE" : "SUCCESS",
                    fields));
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(job);
        } catch (RuntimeException ex) {
            // C9.2 — failures before the job is even persisted.
            meterRegistry.counter(
                    "rag.ingest.failures.total",
                    Tags.of("tenant", tenant, "kbId", body.kbId,
                            "stage", "submit"))
                    .increment();
            // Audit — KB_INGEST with FAILURE outcome so the compliance
            // log captures that the request FAILED (not just that one
            // was attempted).
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            fields.put("documentId", body.documentId);
            audit.record(AuditEvent.of(
                    AuditEvent.Type.KB_INGEST,
                    tenant,
                    request.getHeader("X-User-Id"),
                    requestId,
                    null,
                    "FAILURE",
                    fields));
            throw ex;
        } finally {
            sample.stop(meterRegistry.timer(
                    "rag.ingest.http.submit.duration",
                    Tags.of("tenant", tenant)));
        }
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
        String tenant = requiredTenant(request, request.getHeader(MdcTenantFilter.HEADER_TENANT));
        String requestId = MdcTenantFilter.requestId(request);
        String actor = request.getHeader("X-User-Id");
        Timer.Sample sample = Timer.start(meterRegistry);
        try {
            IngestJob job = ingestService.publish(jobId);
            // C9.2 — a successful publish is the most consequential
            // ingest event (it flips the active index live), so we count
            // it on its own tag so the dashboard can show "publishes per
            // minute" alongside the document-submission counter. The
            // IngestJob record doesn't carry kbId directly (the
            // controller's job is the source of truth on the wire), so
            // we tag with kbVersion as a stable secondary dimension.
            meterRegistry.counter(
                    "rag.ingest.documents.total",
                    Tags.of("tenant", tenant,
                            "kbVersion", job.kbVersion() == null ? "unknown" : job.kbVersion(),
                            "outcome", "PUBLISHED"))
                    .increment();
            // Audit — KB_PUBLISH. This is the single most important
            // audit event in the system: it marks the moment a KB
            // version went live and is now serving traffic. Auditors
            // reconstruct "when did this content become visible to
            // users?" purely from this event.
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("kbVersion", job.kbVersion());
            fields.put("documentId", job.documentId());
            fields.put("totalChunks", job.totalChunks());
            fields.put("embeddedChunks", job.embeddedChunks());
            fields.put("upsertedChunks", job.upsertedChunks());
            fields.put("failedChunks", job.failedChunks());
            audit.record(AuditEvent.of(
                    AuditEvent.Type.KB_PUBLISH,
                    tenant,
                    actor,
                    requestId,
                    jobId,
                    "SUCCESS",
                    fields));
            return job;
        } catch (RuntimeException ex) {
            // C9.2 — publish failures are critical (the operator
            // expected the KB to flip and it didn't) so we count them
            // on a dedicated tag.
            meterRegistry.counter(
                    "rag.ingest.failures.total",
                    Tags.of("tenant", tenant, "kbId", "unknown",
                            "stage", "publish"))
                    .increment();
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("error", ex.getClass().getSimpleName() + ": " + ex.getMessage());
            audit.record(AuditEvent.of(
                    AuditEvent.Type.KB_PUBLISH,
                    tenant,
                    actor,
                    requestId,
                    jobId,
                    "FAILURE",
                    fields));
            throw ex;
        } finally {
            sample.stop(meterRegistry.timer(
                    "rag.ingest.http.publish.duration",
                    Tags.of("tenant", tenant)));
        }
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
