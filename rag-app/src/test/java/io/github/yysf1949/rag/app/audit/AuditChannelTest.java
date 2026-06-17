package io.github.yysf1949.rag.app.audit;

import io.github.yysf1949.rag.core.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the {@link AuditChannel} — design spec §21 + checklist §2.4.
 *
 * <p>The test passes a Mockito mock for the SLF4J logger so we can assert
 * the EXACT message handed to SLF4J (the kv payload the file appender
 * prints verbatim), and the EXACT MDC keys set + cleaned. We also verify
 * the never-throw contract under a failing logger.</p>
 */
class AuditChannelTest {

    @Test
    void recordEmitsKvPayloadWithAllFields() {
        Logger mockLogger = mock(Logger.class);
        AuditChannel channel = new AuditChannel(mockLogger);

        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("modelId", "Qwen/Qwen2.5-7B-Instruct");
        fields.put("latencyMs", 1234L);
        fields.put("promptLength", 4096);

        AuditEvent event = AuditEvent.of(
                AuditEvent.Type.LLM_CALL,
                "tenant-A",
                "user-001",
                "req-abc-123",
                "query-hash-xyz",
                "SUCCESS",
                fields);
        channel.record(event);

        // We expect exactly one logger.info() call with the kv payload as
        // the single argument (no placeholders). The exact message is
        // verified by re-deriving it via the package-private toJson()
        // method so the test stays in lock-step with the production
        // wire format.
        verify(mockLogger, atLeastOnce()).info(anyString());
        verify(mockLogger).info(expectedKv(event));
    }

    @Test
    void recordSetsAndCleansMdcKeys() {
        Logger mockLogger = mock(Logger.class);
        AuditChannel channel = new AuditChannel(mockLogger);

        AuditEvent event = AuditEvent.of(
                AuditEvent.Type.KB_PUBLISH,
                "tenant-X",
                "u-007",
                "req-zzz-999",
                "job-42");
        channel.record(event);

        // The MDC keys must be set BEFORE the SLF4J call and removed
        // AFTER (so the caller's MDC is not polluted). We can't observe
        // ordering from Mockito — instead we verify post-condition that
        // the keys are absent (record() always restores MDC in finally).
        org.slf4j.MDC.clear();
        channel.record(event);
        assertEquals(null, org.slf4j.MDC.get("audit.type"),
                "MDC must be cleaned after record() returns");
        assertEquals(null, org.slf4j.MDC.get("audit.tenant"),
                "MDC must be cleaned after record() returns");
        assertEquals(null, org.slf4j.MDC.get("audit.actor"),
                "MDC must be cleaned after record() returns");
    }

    @Test
    void recordNeverThrowsWhenLoggerFails() {
        Logger mockLogger = mock(Logger.class);
        doThrow(new RuntimeException("disk full")).when(mockLogger).info(anyString());
        AuditChannel channel = new AuditChannel(mockLogger);

        AuditEvent event = AuditEvent.of(
                AuditEvent.Type.LLM_CALL,
                "tenant-A",
                "u-001",
                "req-1",
                "qhash-1");

        // No exception bubbles up — the channel MUST absorb appender
        // failures to keep the LLM call path bulletproof.
        channel.record(event);
        assertEquals(1, channel.errorCount(),
                "failed emits must be tracked on errorCount() for alerting");
        assertEquals(0, channel.emittedCount(),
                "successful emits counter must not move on failure");
    }

    @Test
    void toJsonRendersAllAuditEventFields() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("modelId", "Qwen");
        fields.put("latencyMs", 250L);
        fields.put("escapeTest", "line1\nline2\t\"quoted\"\\back");

        AuditEvent event = new AuditEvent(
                Instant.parse("2026-06-17T12:00:00Z"),
                AuditEvent.Type.LLM_CALL,
                "tenant-A",
                "u-1",
                "req-1",
                "q-1",
                "SUCCESS",
                fields);

        String json = AuditChannel.toJson(event);
        assertNotNull(json);
        assertTrue(json.contains("audit.type=LLM_CALL"), json);
        assertTrue(json.contains("audit.tenantId=tenant-A"), json);
        assertTrue(json.contains("audit.actorId=\"u-1\""), json);
        assertTrue(json.contains("audit.requestId=\"req-1\""), json);
        assertTrue(json.contains("audit.resourceId=\"q-1\""), json);
        assertTrue(json.contains("audit.outcome=SUCCESS"), json);
        assertTrue(json.contains("audit.ts=2026-06-17T12:00:00Z"), json);
        // nested fields are flat-prefixed, order matches LinkedHashMap
        // insertion (latencyMs first, then modelId, then escapeTest)
        assertTrue(json.contains("audit.fields.latencyMs=250"), json);
        assertTrue(json.contains("audit.fields.modelId=\"Qwen\""), json);
        // JSON-escape correctness
        assertTrue(json.contains("audit.fields.escapeTest=\"line1\\nline2\\t\\\"quoted\\\"\\\\back\""), json);
    }

    @Test
    void emittedCountIncrementsOnSuccess() {
        Logger mockLogger = mock(Logger.class);
        AuditChannel channel = new AuditChannel(mockLogger);
        for (int i = 0; i < 3; i++) {
            channel.record(AuditEvent.of(
                    AuditEvent.Type.KB_INGEST, "t-1", "u-1", "r-1", "j-1"));
        }
        assertEquals(3, channel.emittedCount());
        assertEquals(0, channel.errorCount());
    }

    @Test
    void toJsonOmitsNullOptionalFields() {
        AuditEvent event = new AuditEvent(
                Instant.parse("2026-06-17T00:00:00Z"),
                AuditEvent.Type.KB_INGEST,
                "tenant-A",
                null, // actorId
                null, // requestId
                null, // resourceId
                "SUCCESS",
                Map.of());
        String json = AuditChannel.toJson(event);
        assertFalse(json.contains("audit.actorId"), json);
        assertFalse(json.contains("audit.requestId"), json);
        assertFalse(json.contains("audit.resourceId"), json);
        // Required fields always present
        assertTrue(json.contains("audit.type=KB_INGEST"));
        assertTrue(json.contains("audit.tenantId=tenant-A"));
        assertTrue(json.contains("audit.outcome=SUCCESS"));
    }

    /** Re-derive the expected wire-format payload from the event. */
    private static String expectedKv(AuditEvent event) {
        return AuditChannel.toJson(event);
    }
}
