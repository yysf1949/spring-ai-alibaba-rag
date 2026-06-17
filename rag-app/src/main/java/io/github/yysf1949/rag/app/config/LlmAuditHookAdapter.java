package io.github.yysf1949.rag.app.config;

import io.github.yysf1949.rag.app.audit.AuditChannel;
import io.github.yysf1949.rag.core.model.AuditEvent;
import io.github.yysf1949.rag.core.port.LlmAuditHook;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Default {@link LlmAuditHook} implementation for rag-app — bridges
 * the Spring-free {@code rag-pipeline} to the Spring-managed
 * {@link AuditChannel}.
 *
 * <h2>Failure contract</h2>
 * <p>Adheres strictly to the {@link LlmAuditHook} contract:</p>
 * <ul>
 *   <li><b>Never throws</b> — the LLM call path MUST NOT abort because
 *       the audit sink is broken. Any {@link AuditChannel} runtime
 *       exception is absorbed silently; the channel already tracks its
 *       own errors on {@code rag.audit.errors.total}.</li>
 *   <li><b>Never blocks</b> — {@link AuditChannel#record} delegates to
 *       the synchronous SLF4J appender, which is itself non-blocking
 *       for {@code ConsoleAppender} / {@code FileAppender} in the
 *       common case. If the deployment swaps in an {@code AsyncAppender}
 *       (recommended for high throughput) the latency is amortised.</li>
 * </ul>
 *
 * <h2>Privacy / redaction</h2>
 * <p>The prompt body can contain user PII. We pass it through verbatim
 * because:</p>
 * <ol>
 *   <li>Compliance readers are the intended audience for the audit
 *       file; they MUST see the actual prompt to verify the LLM
 *       didn't leak data.</li>
 *   <li>The audit file is access-controlled at the deployment layer
 *       (file permissions / Kafka ACLs) — see RUNBOOK §4.</li>
 *   <li>User PII is already redacted at the {@link
 *       io.github.yysf1949.rag.pipeline.context.DefaultSensitiveDataRedactor}
 *       BEFORE the prompt is assembled (spec §15.3), so the audit
 *       payload inherits that redaction.</li>
 * </ol>
 */
final class LlmAuditHookAdapter implements LlmAuditHook {

    private final AuditChannel channel;

    LlmAuditHookAdapter(AuditChannel channel) {
        this.channel = channel;
    }

    @Override
    public void onLlmCall(
            String tenantId,
            String userId,
            String sessionId,
            String queryHash,
            String modelId,
            String promptTemplate,
            String promptBody,
            String completion,
            long latencyMs,
            String outcome) {
        try {
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("modelId", modelId == null ? "unknown" : modelId);
            fields.put("promptTemplate", promptTemplate == null ? "unknown" : promptTemplate);
            fields.put("promptLength", promptBody == null ? 0 : promptBody.length());
            fields.put("completionLength", completion == null ? 0 : completion.length());
            fields.put("latencyMs", latencyMs);
            // Capture only the first 4 KB of each side — the audit file
            // is for compliance, not for full-text search. Operators
            // who need the full prompt/completion can correlate by
            // queryHash against the LLM provider's access log.
            fields.put("promptPreview", preview(promptBody, 4096));
            fields.put("completionPreview", preview(completion, 4096));
            // Per-call: pin sessionId for cross-call correlation.
            if (sessionId != null) {
                fields.put("sessionId", sessionId);
            }
            channel.record(AuditEvent.of(
                    AuditEvent.Type.LLM_CALL,
                    tenantId == null ? "__global__" : tenantId,
                    userId,
                    null, // requestId is already on MDC; the audit appender
                          // reads it from there via the audit.requestId
                          // key set by AuditChannel.record.
                    queryHash, // resourceId
                    outcome == null ? "SUCCESS" : outcome,
                    fields));
        } catch (RuntimeException e) {
            // Defensive — AuditChannel.record already catches and counts
            // its own errors, but the contract on the hook is "never
            // throw" so we absorb even adapter-side exceptions to keep
            // the LLM call path bulletproof.
        }
    }

    private static String preview(String s, int max) {
        if (s == null) {
            return "";
        }
        if (s.length() <= max) {
            return s;
        }
        return s.substring(0, max) + "...[truncated " + (s.length() - max) + " chars]";
    }
}
