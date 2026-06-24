package io.github.yysf1949.rag.app.web;

import io.github.yysf1949.rag.app.config.ExperimentConfig;
import io.github.yysf1949.rag.app.config.MdcTenantFilter;
import io.github.yysf1949.rag.core.port.QAService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;

/**
 * Records A/B outcomes — Phase 39 / R14.
 *
 * <h2>Endpoint</h2>
 * <pre>
 *   POST /api/qa/feedback
 *   Headers: X-Tenant-Id (required)
 *   Body:    { userId, sessionId?, experimentName, variantId, positive, rawText? }
 * </pre>
 *
 * <p>When the UI shows a thumbs-up / thumbs-down (or the user clicks a
 * citation marker which we treat as an implicit "the answer was useful"),
 * the UI posts here. The handler delegates to
 * {@link QAService#recordExperimentOutcome} so the outcome lands on the
 * right {@link io.github.yysf1949.rag.pipeline.qa.experiment.ExperimentMetricsRecorder}.</p>
 *
 * <p>Returns {@code 204 No Content} on success; unknown experiment /
 * variant ids are logged and silently ignored (no {@code 404}) so a
 * stale UI tab can't break the request flow.</p>
 */
@RestController
@RequestMapping("/api")
@Tag(name = "QA Feedback", description = "Record user feedback for A/B experiments.")
public class CitationFeedbackController {

    private final QAService qaService;

    public CitationFeedbackController(QAService qaService) {
        this.qaService = qaService;
    }

    @PostMapping("/qa/feedback")
    @Operation(summary = "Record a positive/negative outcome for the named experiment variant.",
            description = "Bridges UI feedback (thumbs-up, citation click) into the experiment recorder.")
    public org.springframework.http.ResponseEntity<Void> record(
            HttpServletRequest request,
            @Valid @RequestBody FeedbackRequest body) {
        String tenantId = requiredTenant(request);
        String experimentName = (body.experimentName == null || body.experimentName.isBlank())
                ? ExperimentConfig.DEFAULT_EXPERIMENT
                : body.experimentName;
        // Don't construct a Query — recordExperimentOutcome never reads
        // rawText, and Query's invariant (rawText must be non-blank)
        // rejects empty/null inputs that the feedback endpoint legitimately
        // sends (the UI often doesn't have the original question at
        // thumbs-up time).
        qaService.recordExperimentOutcome(experimentName, body.variantId, body.positive, null);
        return org.springframework.http.ResponseEntity.noContent().build();
    }

    private static String requiredTenant(HttpServletRequest request) {
        String tenant = request.getHeader(MdcTenantFilter.HEADER_TENANT);
        if (tenant == null || tenant.isBlank()) {
            throw new RagController.MissingTenantException(
                    "Missing " + MdcTenantFilter.HEADER_TENANT + " header.");
        }
        return Objects.requireNonNull(tenant);
    }

    public static class FeedbackRequest {
        public String userId;
        public String sessionId;
        @NotBlank public String experimentName;
        @NotBlank public String variantId;
        public boolean positive;
        /** Optional — the original query text. Used only to enrich MDC. */
        public String rawText;
    }
}
