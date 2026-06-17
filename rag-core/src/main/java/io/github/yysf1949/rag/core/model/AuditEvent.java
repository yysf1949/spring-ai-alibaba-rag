package io.github.yysf1949.rag.core.model;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * Structured audit event — design spec §21 (audit log) + checklist §2.4.
 *
 * <p>Audit events cover two regulatory/compliance axes:</p>
 * <ol>
 *   <li><b>Admin / write actions</b> — knowledge base publish, tenant
 *       configuration changes, deprecation. The reviewer must be able to
 *       reconstruct who changed what and when.</li>
 *   <li><b>LLM provenance</b> — every LLM call (Q&amp;A, summary, eval)
 *       records the queryHash, the LLM's full response, the prompt
 *       template version, and the model id. This is the GDPR / data-lineage
 *       trail that lets a user request deletion and lets operators trace
 *       hallucinated answers back to a specific model + prompt + KB version.</li>
 * </ol>
 *
 * <p>Events are emitted to a dedicated SLF4J logger
 * ({@code AUDIT_LOGGER_NAME = "audit"}) so they can be routed to a separate
 * appender (file, Kafka, or ELK) without polluting the application's
 * business logs. Fields are also reachable as MDC keys for the duration of
 * the emitting call so downstream log lines inherit them automatically.</p>
 *
 * <p>The class is a value record — no Spring, no I/O. The actual emission
 * lives in {@code rag-app.audit.AuditChannel}.</p>
 *
 * @param timestamp    wall-clock time at which the event was created (UTC)
 * @param type         event type discriminator (see {@link Type})
 * @param tenantId     the tenant this event is scoped to; never null
 *                     (admin actions on a tenant-less resource use
 *                     {@code "__global__"})
 * @param actorId      the user / service-account that triggered the event;
 *                     may be null for system-emitted events
 * @param requestId    the request correlation id (mirrors MDC requestId);
 *                     may be null for events emitted outside an HTTP scope
 * @param resourceId   primary resource identifier (jobId / kbId / queryHash /
 *                     sessionId depending on type); may be null
 * @param outcome      "SUCCESS" / "FAILURE" / "DENIED" — coarse result
 * @param fields       free-form typed payload (the event-type-specific
 *                     fields — prompt template, model id, chunk count, etc.)
 */
public record AuditEvent(
        Instant timestamp,
        Type type,
        String tenantId,
        String actorId,
        String requestId,
        String resourceId,
        String outcome,
        Map<String, Object> fields
) {

    public AuditEvent {
        Objects.requireNonNull(timestamp, "timestamp");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(tenantId, "tenantId");
        // requestId, actorId, resourceId, outcome, fields are all optional
        fields = fields == null ? Map.of() : Map.copyOf(fields);
        if (outcome == null) {
            outcome = "SUCCESS";
        }
    }

    /**
     * The event-type taxonomy. Adding a new type requires adding a matching
     * `toString` for log readers AND a matching test case in
     * {@code AuditChannelTest}.
     */
    public enum Type {
        /** {@code POST /api/ingest/{jobId}/publish} succeeded — KB is now live. */
        KB_PUBLISH,
        /** {@code POST /api/ingest} accepted (job is PENDING/READY/FAILED). */
        KB_INGEST,
        /** Ingest job ran but its inner pipeline (chunk/embed/write) failed. */
        INGEST_FAIL,
        /** {@code LLM.generate(prompt)} was called and returned. */
        LLM_CALL,
        /** Tenant configuration change (feature flag, kbId whitelist). */
        TENANT_CONFIG_CHANGE
    }

    /** Convenience: build with a fresh UTC timestamp and an empty fields map. */
    public static AuditEvent of(
            Type type, String tenantId, String actorId, String requestId,
            String resourceId, String outcome, Map<String, Object> fields) {
        return new AuditEvent(
                Instant.now(), type, tenantId, actorId, requestId,
                resourceId, outcome == null ? "SUCCESS" : outcome, fields);
    }

    /** Convenience: SUCCESS outcome, empty fields. */
    public static AuditEvent of(
            Type type, String tenantId, String actorId, String requestId,
            String resourceId) {
        return of(type, tenantId, actorId, requestId, resourceId, "SUCCESS", Map.of());
    }
}
