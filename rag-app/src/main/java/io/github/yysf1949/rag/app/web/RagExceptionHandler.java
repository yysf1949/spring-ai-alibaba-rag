package io.github.yysf1949.rag.app.web;

import io.github.yysf1949.rag.core.exception.EmbeddingUnavailableException;
import io.github.yysf1949.rag.core.exception.VectorStoreUnavailableException;
import jakarta.validation.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

import java.time.Instant;
import java.util.Map;

/**
 * Centralised exception → HTTP mapping — design spec §10.
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
 */
@ControllerAdvice
public class RagExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RagExceptionHandler.class);

    @ExceptionHandler(RagController.MissingTenantException.class)
    public ResponseEntity<Map<String, Object>> missingTenant(RagController.MissingTenantException ex) {
        return body(HttpStatus.UNAUTHORIZED, "missing_tenant", ex.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        String msg = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .reduce((a, b) -> a + "; " + b)
                .orElse(ex.getMessage());
        return body(HttpStatus.BAD_REQUEST, "validation_failed", msg);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Map<String, Object>> constraint(ConstraintViolationException ex) {
        return body(HttpStatus.BAD_REQUEST, "constraint_violation", ex.getMessage());
    }

    @ExceptionHandler(VectorStoreUnavailableException.class)
    public ResponseEntity<Map<String, Object>> vectorStoreDown(VectorStoreUnavailableException ex) {
        log.warn("Vector store unavailable: {}", ex.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "vector_store_unavailable",
                "Vector store is currently unavailable. Please retry.",
                Map.of(HttpHeaders.RETRY_AFTER, "30"));
    }

    @ExceptionHandler(EmbeddingUnavailableException.class)
    public ResponseEntity<Map<String, Object>> embeddingDown(EmbeddingUnavailableException ex) {
        log.warn("Embedding gateway unavailable: {}", ex.getMessage());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "embedding_unavailable",
                "Embedding service is currently unavailable. Please retry.",
                Map.of(HttpHeaders.RETRY_AFTER, "30"));
    }

    @ExceptionHandler(RuntimeException.class)
    public ResponseEntity<Map<String, Object>> unexpected(RuntimeException ex) {
        log.error("Unhandled exception in QA controller", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred. The incident has been logged.");
    }

    private static ResponseEntity<Map<String, Object>> body(HttpStatus status, String code, String msg) {
        return body(status, code, msg, Map.of());
    }

    private static ResponseEntity<Map<String, Object>> body(
            HttpStatus status, String code, String msg, Map<String, String> headers) {
        var b = ResponseEntity.status(status)
                .body(Map.<String, Object>of(
                        "timestamp", Instant.now().toString(),
                        "status", status.value(),
                        "error", code,
                        "message", msg));
        for (var e : headers.entrySet()) {
            b = ResponseEntity.status(status).header(e.getKey(), e.getValue())
                    .body(Map.<String, Object>of(
                            "timestamp", Instant.now().toString(),
                            "status", status.value(),
                            "error", code,
                            "message", msg));
        }
        return b;
    }
}
