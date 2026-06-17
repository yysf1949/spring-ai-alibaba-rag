package io.github.yysf1949.rag.app.audit;

import io.github.yysf1949.rag.core.model.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Centralised audit-event sink — design spec §21 + checklist §2.4.
 *
 * <h2>Why a separate logger (not the application logger)?</h2>
 * <p>Audit events have a fundamentally different lifetime than business
 * logs:</p>
 * <ul>
 *   <li>They MUST be retained for ≥ 6 months (regulatory).</li>
 *   <li>They MUST be tamper-resistant (read-only file / append-only Kafka).</li>
 *   <li>They MUST NOT be silently dropped by log-level filtering or by
 *       log-sampling rate-limits.</li>
 *   <li>They have a fixed schema (per {@link AuditEvent.Type}) and are
 *       consumed by compliance tooling, not by humans tailing stdout.</li>
 * </ul>
 *
 * <p>The dedicated logger name {@value #AUDIT_LOGGER_NAME} is the
 * integration point — {@code logback-spring.xml} binds it to its own
 * appender chain (a JSON file at {@code logs/audit.json} + an OPTIONAL
 * Kafka appender in production). The application logger ({@code INFO}
 * for {@code io.github.yysf1949.rag}) is never invoked here, so log
 * level / sampling on the app logger can never suppress audit.</p>
 *
 * <h2>Why hand-rolled JSON (not logstash-encoder)?</h2>
 * <p>We render the event as a single {@code kv()} payload string passed
 * to {@link Logger#info(String, Object...)} so SLF4J treats the WHOLE
 * message as a single argument — the {@code audit} appender's
 * {@code <message>}-pattern placeholder copies it verbatim into the file
 * line, no encoder magic required. This keeps the rag-app fat-jar small
 * (logstash-encoder is ~600 KB) and lets unit tests assert against the
 * exact emitted string without a JSON parsing dependency. Compliance
 * tooling that wants structured fields can split on the first {@code =}
 * and the {@code ;} field separator — see {@link #toJson(AuditEvent)}
 * for the exact grammar.</p>
 *
 * <h2>Threading & ordering</h2>
 * <p>{@link #record(AuditEvent)} is non-blocking and synchronous to SLF4J.
 * The dedicated appender chain runs in the same thread; for
 * multi-million-event workloads consider an {@code AsyncAppender}
 * wrapper in {@code logback-spring.xml} — left as a deployment choice
 * so the unit tests can run synchronously and assert ordering.</p>
 *
 * <h2>Failure handling</h2>
 * <p>If the underlying appender throws (disk full, Kafka down) SLF4J
 * catches the error and logs to the standard error stream — we never
 * propagate audit failures to the business call path, because the
 * alternative (failing a publish because the audit file is full) is
 * worse than the gap. Operators MUST alert on appender errors via the
 * dedicated app-metric {@code rag.audit.errors.total}.</p>
 */
@Component
public class AuditChannel {

    /**
     * SLF4J logger name — logback-spring.xml binds this to the audit
     * appender chain. Public so {@code logback-spring.xml} can reference
     * it without a typo.
     */
    public static final String AUDIT_LOGGER_NAME = "audit";

    private static final Logger BUSINESS_LOG = LoggerFactory.getLogger("io.github.yysf1949.rag.audit");

    private final Logger audit;
    private final AtomicLong emitted = new AtomicLong();
    private final AtomicLong errored = new AtomicLong();

    public AuditChannel() {
        this(LoggerFactory.getLogger(AUDIT_LOGGER_NAME));
    }

    /** Constructor used by unit tests to inject a mocked logger. */
    AuditChannel(Logger audit) {
        this.audit = Objects.requireNonNull(audit, "audit logger");
    }

    /**
     * Emit an audit event. Always returns immediately. Failures inside the
     * appender are absorbed by SLF4J and tracked via
     * {@link #errorCount()}; callers MUST NOT treat the return value as a
     * "the event was durably persisted" signal — for that, configure a
     * {@code SyncAppender} or rely on the application-metric alert.
     */
    public void record(AuditEvent event) {
        Objects.requireNonNull(event, "event");
        // Pin the per-event correlation keys on MDC for the duration of
        // the SLF4J call so any log line emitted by the appender itself
        // inherits them. MDC is thread-local; we restore on exit so we
        // don't leak into the caller's MDC.
        Map<String, String> mdcSnapshot = new LinkedHashMap<>();
        try {
            putMdc(mdcSnapshot, "audit.type", event.type().name());
            putMdc(mdcSnapshot, "audit.outcome", event.outcome());
            if (event.tenantId() != null) {
                putMdc(mdcSnapshot, "audit.tenant", event.tenantId());
            }
            if (event.actorId() != null) {
                putMdc(mdcSnapshot, "audit.actor", event.actorId());
            }
            if (event.requestId() != null) {
                putMdc(mdcSnapshot, "audit.requestId", event.requestId());
            }
            if (event.resourceId() != null) {
                putMdc(mdcSnapshot, "audit.resource", event.resourceId());
            }
            // Render the event as a single kv-payload string. We pass it
            // as the SLF4J MESSAGE (not as a {} placeholder) so the
            // appender prints it verbatim — no field-position dependency
            // on the appender pattern.
            audit.info(toJson(event));
            emitted.incrementAndGet();
        } catch (RuntimeException e) {
            // Audit MUST NOT propagate — the business action that triggered
            // it (e.g. publish) is more important than a failed log write.
            // We track the failure and emit a single business-log line so
            // operators see it; the {@code rag.audit.errors.total} gauge is
            // the alerting signal.
            errored.incrementAndGet();
            BUSINESS_LOG.error("audit emission failed type={} tenant={} err={}",
                    event.type(), event.tenantId(), e.toString());
        } finally {
            // Restore MDC — remove only the keys we added.
            for (String key : mdcSnapshot.keySet()) {
                MDC.remove(key);
            }
        }
    }

    /**
     * Serialise an {@link AuditEvent} into a single-line {@code k=v;k=v}
     * payload — the same grammar the audit appender's pattern prints
     * verbatim. Exposed package-private so unit tests can assert against
     * the exact wire format without touching SLF4J.
     *
     * <p>Why this shape: every key is preceded by {@code "audit."} so a
     * {@code grep -E '"audit\.[a-zA-Z]+"'} works on the JSON file output.
     * String values are JSON-escaped (double-quote, backslash, control
     * chars); numeric/boolean values are passed through; nested fields
     * are flattened with {@code .} separators (e.g.
     * {@code fields.chunkCount=42}).</p>
     */
    static String toJson(AuditEvent event) {
        StringBuilder sb = new StringBuilder(256);
        appendKv(sb, "audit.type", event.type().name(), false);
        appendKv(sb, "audit.tenantId", event.tenantId(), false);
        appendKv(sb, "audit.actorId", event.actorId(), true);
        appendKv(sb, "audit.requestId", event.requestId(), true);
        appendKv(sb, "audit.resourceId", event.resourceId(), true);
        appendKv(sb, "audit.outcome", event.outcome(), false);
        appendKv(sb, "audit.ts", event.timestamp().toString(), false);
        for (Map.Entry<String, Object> e : event.fields().entrySet()) {
            String key = "audit.fields." + e.getKey();
            if (e.getValue() == null) {
                appendKv(sb, key, null, true);
            } else if (e.getValue() instanceof Number || e.getValue() instanceof Boolean) {
                appendKv(sb, key, e.getValue().toString(), false);
            } else {
                appendKv(sb, key, e.getValue().toString(), true);
            }
        }
        return sb.toString();
    }

    private static void appendKv(StringBuilder sb, String key, String value, boolean quote) {
        if (value == null) {
            return; // skip null optional fields to keep the line short
        }
        if (sb.length() > 0) {
            sb.append(';');
        }
        sb.append(key).append('=');
        if (quote) {
            sb.append('"').append(escapeJson(value)).append('"');
        } else {
            sb.append(escapeJson(value));
        }
    }

    /** Minimal JSON string escape: backslash, double-quote, control chars. */
    static String escapeJson(String s) {
        StringBuilder out = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\' -> out.append("\\\\");
                case '"' -> out.append("\\\"");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Total events successfully handed to SLF4J (does NOT prove persistence). */
    public long emittedCount() {
        return emitted.get();
    }

    /** Total events whose emit threw — alerts on this gauge. */
    public long errorCount() {
        return errored.get();
    }

    private static void putMdc(Map<String, String> snapshot, String key, String value) {
        snapshot.put(key, value);
        MDC.put(key, value);
    }
}
