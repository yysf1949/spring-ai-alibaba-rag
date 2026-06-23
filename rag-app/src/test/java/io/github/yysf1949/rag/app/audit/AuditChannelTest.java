package io.github.yysf1949.rag.app.audit;

import io.github.yysf1949.rag.core.model.AuditEvent;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.never;
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

    // ──────────────────────────────────────────────────────────────────────
    // Phase 34-T34b — recordTenantConfigChange helper
    // ──────────────────────────────────────────────────────────────────────

    @Test
    void recordTenantConfigChange_emitsTypedEventWithOldAndNewConfig() {
        Logger mockLogger = mock(Logger.class);
        AuditChannel channel = new AuditChannel(mockLogger);

        channel.recordTenantConfigChange(
                "tenant-A",
                "{\"kbWhitelist\":[\"kb-1\"]}",
                "{\"kbWhitelist\":[\"kb-1\",\"kb-2\"]}",
                "admin-1",
                "req-42");

        // Capture the AuditEvent that record(...) received and verify its
        // shape — this is the contract the admin endpoint depends on.
        ArgumentCaptor<AuditEvent> captor = ArgumentCaptor.forClass(AuditEvent.class);
        org.mockito.Mockito.verify(mockLogger, atLeastOnce()).info(anyString());
        org.mockito.Mockito.verify(mockLogger).info(anyString());
        // We can't easily capture the AuditEvent through the SLF4J mock
        // (the channel builds it internally), so verify via the wire-format
        // string instead — the AuditChannel.toJson() output is deterministic.
        ArgumentCaptor<String> kvCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockLogger).info(kvCaptor.capture());
        String kv = kvCaptor.getValue();

        assertTrue(kv.contains("audit.type=TENANT_CONFIG_CHANGE"), kv);
        assertTrue(kv.contains("audit.tenantId=tenant-A"), kv);
        assertTrue(kv.contains("audit.actorId=\"admin-1\"")
                        && kv.contains("admin-1"),
                "actorId must be the JWT subject \"admin-1\": " + kv);
        assertTrue(kv.contains("audit.requestId=\"req-42\"")
                        && kv.contains("req-42"),
                "requestId must be \"req-42\": " + kv);
        assertTrue(kv.contains("audit.resourceId=\"tenant-A\""), kv);
        assertTrue(kv.contains("audit.outcome=SUCCESS"), kv);
        // The kv wire format escapes inner JSON quotes with a literal
        // backslash — match the same shape via "\\\"" (Java escape of \").
        assertTrue(kv.contains("audit.fields.oldConfig=\"{\\\"kbWhitelist\\\":[\\\"kb-1\\\"]}\""), kv);
        assertTrue(kv.contains("audit.fields.newConfig=\"{\\\"kbWhitelist\\\":[\\\"kb-1\\\",\\\"kb-2\\\"]}\""), kv);
    }

    @Test
    void recordTenantConfigChange_omitsOldConfigWhenNull() {
        Logger mockLogger = mock(Logger.class);
        AuditChannel channel = new AuditChannel(mockLogger);

        // First-write case — oldConfig is null, the wire format MUST NOT
        // include the oldConfig key at all (audit receiver splits on `=`).
        channel.recordTenantConfigChange(
                "tenant-new",
                null /* first write */,
                "{\"v\":1}",
                "admin-2",
                null);

        ArgumentCaptor<String> kvCaptor = ArgumentCaptor.forClass(String.class);
        org.mockito.Mockito.verify(mockLogger).info(kvCaptor.capture());
        String kv = kvCaptor.getValue();

        assertTrue(kv.contains("audit.type=TENANT_CONFIG_CHANGE"), kv);
        assertFalse(kv.contains("audit.fields.oldConfig"),
                "oldConfig must be absent on first write (not present-and-null), got: " + kv);
        assertTrue(kv.contains("audit.fields.newConfig=\"{\\\"v\\\":1}\""), kv);
    }

    @Test
    void recordTenantConfigChange_rejectsNullTenantAndNullNewConfig() {
        Logger mockLogger = mock(Logger.class);
        AuditChannel channel = new AuditChannel(mockLogger);

        try {
            channel.recordTenantConfigChange(null, "x", "y", "u", null);
            assertTrue(false, "expected NPE for null tenantId");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("tenantId"));
        }

        try {
            channel.recordTenantConfigChange("t", "x", null, "u", null);
            assertTrue(false, "expected NPE for null newConfig");
        } catch (NullPointerException expected) {
            assertTrue(expected.getMessage().contains("newConfig"));
        }

        // Neither call may have invoked the logger.
        org.mockito.Mockito.verify(mockLogger, never()).info(anyString());
    }

    @Test
    void recordTenantConfigChange_absorbedByLoggerFailure() {
        Logger mockLogger = mock(Logger.class);
        org.mockito.Mockito.doThrow(new RuntimeException("disk full"))
                .when(mockLogger).info(anyString());
        AuditChannel channel = new AuditChannel(mockLogger);

        // MUST NOT throw — admin endpoint semantics depend on this.
        channel.recordTenantConfigChange(
                "tenant-A", "old", "new", "admin", "req");

        assertEquals(1, channel.errorCount());
        assertEquals(0, channel.emittedCount());
    }
}
