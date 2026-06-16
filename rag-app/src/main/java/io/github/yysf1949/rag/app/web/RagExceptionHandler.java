package io.github.yysf1949.rag.app.web;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

/**
 * Centralised exception → RFC 7807 {@code application/problem+json}
 * mapping — design spec §10.
 *
 * <h2>Mapping table</h2>
 * <table>
 *   <tr><th>Exception</th><th>Status</th><th>Why</th></tr>
 *   <tr><td>{@link RagController.MissingTenantException}</td><td>401</td>
 *       <td>Caller forgot the tenant header</td></tr>
 *   <tr><td>{@link MethodArgumentNotValidException},
 *           {@link ConstraintViolationException}</td><td>400</td>
 *       <td>Bean validation failure</td></tr>
 *   <tr><td>{@link VectorStoreUnavailableException}</td><td>503 + Retry-After</td>
 *       <td>Redis Stack down (we serve no chunks)</td></tr>
 *   <tr><td>{@link EmbeddingUnavailableException}</td><td>503 + Retry-After</td>
 *       <td>DashScope down (we cannot embed queries)</td></tr>
 *   <tr><td>any other {@link RuntimeException}</td><td>500</td>
 *       <td>Unexpected — log full stack, return generic message</td></tr>
 * </table>
 *
 * <h2>Why ProblemDetail</h2>
 * Spring Framework 6 ships {@link ProblemDetail} which implements RFC 7807
 * out of the box. Using it means: (a) clients can rely on the standard
 * {@code type / title / status / detail / instance} fields; (b) the
 * {@code Content-Type} becomes {@code application/problem+json}; (c) no
 * extra dependency (no springdoc-openapi, no spring-hateoas).
 */
@RestControllerAdvice
public class RagExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RagExceptionHandler.class);

    /** Base URI for problem types. {@code /problems/missing-tenant} etc. */
    private static final URI PROBLEM_BASE = URI.create("https://yysf1949.io/problems/");

    @ExceptionHandler(RagController.MissingTenantException.class)
    public ProblemDetail missingTenant(RagController.MissingTenantException ex) {
        return problem(HttpStatus.UNAUTHORIZED, "missing-tenant", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(ex.getMessage());
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation-failed", msg);
        pd.setProperty("violations", ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> java.util.Map.of(
                        "field", fe.getField(),
                        "rejectedValue", String.valueOf(fe.getRejectedValue()),
                        "message", fe.getDefaultMessage()))
                .toList());
        return pd;
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ProblemDetail constraint(ConstraintViolationException ex) {
        return problem(HttpStatus.BAD_REQUEST, "constraint-violation", ex.getMessage());
    }

    @ExceptionHandler(VectorStoreUnavailableException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> vectorStoreDown(VectorStoreUnavailableException ex) {
        log.warn("Vector store unavailable: {} (cause: {})", ex.getMessage(),
                ex.getCause() != null ? ex.getCause().toString() : "n/a");
        return withRetryAfter(problem(HttpStatus.SERVICE_UNAVAILABLE,
                "vector-store-unavailable",
                "Vector store is currently unavailable. Please retry."));
    }

    @ExceptionHandler(EmbeddingUnavailableException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> embeddingDown(EmbeddingUnavailableException ex) {
        log.warn("Embedding gateway unavailable: {}", ex.getMessage());
        return withRetryAfter(problem(HttpStatus.SERVICE_UNAVAILABLE,
                "embedding-unavailable",
                "Embedding service is currently unavailable. Please retry."));
    }

    @ExceptionHandler(IngestController.IngestJobNotFoundException.class)
    public org.springframework.http.ResponseEntity<ProblemDetail> ingestJobNotFound(
            IngestController.IngestJobNotFoundException ex) {
        log.info("Ingest job not found: {}", ex.getMessage());
        return withRetryAfter(problem(HttpStatus.NOT_FOUND,
                "ingest-job-not-found",
                ex.getMessage()));
    }

    @ExceptionHandler(RuntimeException.class)
    public ProblemDetail unexpected(RuntimeException ex) {
        log.error("Unhandled exception in QA controller", ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "An unexpected error occurred. The incident has been logged.");
    }

    // ─── helpers ─────────────────────────────────────────────────────────

    private static ProblemDetail problem(HttpStatus status, String slug, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(PROBLEM_BASE.resolve(slug));
        pd.setTitle(slug);
        pd.setProperty("timestamp", Instant.now().toString());
        return pd;
    }

    private static org.springframework.http.ResponseEntity<ProblemDetail> withRetryAfter(ProblemDetail pd) {
        return org.springframework.http.ResponseEntity.status(pd.getStatus())
                .header(HttpHeaders.RETRY_AFTER, "30")
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(pd);
    }
}