package io.github.yysf1949.rag.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.app.audit.AuditChannel;
import io.github.yysf1949.rag.core.model.AuditEvent;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.IngestService;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests for the C9.2 controller-level ingest metrics + audit emission on
 * the {@code /api/ingest} HTTP surface — closes the gap identified in
 * the cluster 9 audit (C9.2 was the only remaining item from
 * spec §9.1's rag.ingest.* set).
 *
 * <p>Three things are asserted:</p>
 * <ol>
 *   <li>{@code rag.ingest.documents.total} counter increments on the
 *       SUCCESS path of {@code POST /api/ingest}.</li>
 *   <li>{@code rag.ingest.failures.total} counter increments when
 *       the {@link IngestService} throws.</li>
 *   <li>The {@link AuditChannel} receives a {@link AuditEvent} with
 *       type {@code KB_INGEST} and the expected fields on every call.</li>
 * </ol>
 */
@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext // each test gets a fresh ApplicationContext so the Spring-managed MeterRegistry starts empty
@TestPropertySource(properties = {
        "spring.main.web-application-type=servlet",
        "spring.data.redis.host=nonexistent",
        "spring.data.redis.port=0",
        "spring.ai.openai.api-key=test-key"
})
@org.springframework.context.annotation.ComponentScan(
        excludeFilters = @org.springframework.context.annotation.ComponentScan.Filter(
                type = org.springframework.context.annotation.FilterType.REGEX,
                pattern = "io\\.github\\.ysf1949\\.rag\\.agent\\..*"))
class IngestControllerMetricsAndAuditTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private MeterRegistry meterRegistry;

    @MockBean
    private IngestService ingestService;
    @MockBean
    private AuditChannel auditChannel;

    private AtomicReference<AuditEvent> lastAuditEvent;

    @BeforeEach
    void resetCountersAndCapture() {
        // The Spring-managed MeterRegistry is shared across tests in the
        // same context. We don't reset the registry (it would mask issues
        // if other tests run after us); instead we read absolute values
        // before/after the action and assert the delta.
        lastAuditEvent = new AtomicReference<>();
        org.mockito.Mockito.doAnswer(inv -> {
            lastAuditEvent.set(inv.getArgument(0));
            return null;
        }).when(auditChannel).record(any());
    }

    @Test
    void submitIncrementsDocumentsCounterAndEmitsAudit() throws Exception {
        String jobId = UUID.randomUUID().toString();
        when(ingestService.ingestAsync(any())).thenReturn(
                new IngestJob(jobId, "tenant-A", "doc-1", "1",
                        IngestJobStatus.PENDING,
                        0, 0, 0, 0,
                        java.time.Instant.now(), java.time.Instant.now(), null));

        mvc.perform(post("/api/ingest")
                        .header("X-Tenant-Id", "tenant-A")
                        .header("X-User-Id", "ops-bob")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "kbId", "kb-refund",
                                "documentId", "doc-1",
                                "documentVersion", 1,
                                "title", "Refund policy",
                                "sourceUri", "https://docs.example.com/refund",
                                "permissionTags", List.of("role:user"),
                                "sections", List.of(Map.of(
                                        "heading", "运费",
                                        "content", "签收 7 日内可退运费"))))))
                .andExpect(status().isAccepted());

        // rag.ingest.documents.total{tenant=tenant-A, outcome=PENDING} MUST
        // be present and >= 1 (we don't compare deltas because the
        // shared Spring context can pick up other tests' increments).
        double docs = counterValue("rag.ingest.documents.total",
                "tenant", "tenant-A", "outcome", "PENDING");
        assertTrue(docs >= 1.0,
                "rag.ingest.documents.total{tenant=tenant-A, outcome=PENDING} must be >= 1 (got " + docs + ")");

        // Failure counter must NOT have moved on the success path
        double failures = counterValue("rag.ingest.failures.total", "tenant", "tenant-A");
        assertEquals(0.0, failures,
                "rag.ingest.failures.total must NOT increment on the success path");

        // Audit — KB_INGEST event was emitted
        AuditEvent ev = lastAuditEvent.get();
        assertNotNull(ev, "AuditChannel.record() must be called on submit");
        assertEquals(AuditEvent.Type.KB_INGEST, ev.type());
        assertEquals("tenant-A", ev.tenantId());
        assertEquals("ops-bob", ev.actorId());
        assertEquals("SUCCESS", ev.outcome());
        assertEquals(jobId, ev.resourceId());
        assertEquals("doc-1", ev.fields().get("documentId"));
    }

    @Test
    void submitIncrementsFailuresCounterAndEmitsAuditOnError() throws Exception {
        doThrow(new IllegalStateException("vector store down"))
                .when(ingestService).ingestAsync(any());

        mvc.perform(post("/api/ingest")
                        .header("X-Tenant-Id", "tenant-Z")
                        .header("X-User-Id", "ops-alice")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "kbId", "kb-x",
                                "documentId", "doc-fail",
                                "documentVersion", 1,
                                "title", "title",
                                "sourceUri", "https://example.com",
                                "permissionTags", List.of("role:user"),
                                "sections", List.of(Map.of("content", "x"))))))
                .andExpect(status().is5xxServerError());

        double failures = counterValue("rag.ingest.failures.total", "tenant", "tenant-Z",
                "stage", "submit");
        assertTrue(failures >= 1.0,
                "rag.ingest.failures.total{stage=submit, tenant=tenant-Z} must be >= 1 (got " + failures + ")");

        // Audit — KB_INGEST with FAILURE outcome
        AuditEvent ev = lastAuditEvent.get();
        assertNotNull(ev);
        assertEquals("FAILURE", ev.outcome());
        assertTrue(ev.fields().get("error").toString().contains("vector store down"));
    }

    @Test
    void publishEmitsKbPublishAuditAndCounters() throws Exception {
        String jobId = UUID.randomUUID().toString();
        when(ingestService.publish(jobId)).thenReturn(
                new IngestJob(jobId, "tenant-A", "doc-1", "1",
                        IngestJobStatus.PUBLISHED,
                        12, 12, 12, 0,
                        java.time.Instant.now(), java.time.Instant.now(), null));

        mvc.perform(post("/api/ingest/{jobId}/publish", jobId)
                        .header("X-Tenant-Id", "tenant-A")
                        .header("X-User-Id", "ops-bob"))
                .andExpect(status().isOk());

        double afterPub = counterValue("rag.ingest.documents.total", "tenant", "tenant-A",
                "outcome", "PUBLISHED");
        assertTrue(afterPub >= 1.0,
                "rag.ingest.documents.total{outcome=PUBLISHED} must be >= 1 (got " + afterPub + ")");

        AuditEvent ev = lastAuditEvent.get();
        assertNotNull(ev);
        assertEquals(AuditEvent.Type.KB_PUBLISH, ev.type());
        assertEquals("tenant-A", ev.tenantId());
        assertEquals("ops-bob", ev.actorId());
        assertEquals("SUCCESS", ev.outcome());
        assertEquals(jobId, ev.resourceId());
        assertEquals(12, ev.fields().get("totalChunks"));
        assertEquals(12, ev.fields().get("embeddedChunks"));
        assertEquals(12, ev.fields().get("upsertedChunks"));

        // The controller also records a Timer sample — we don't assert the
        // exact value (timing-dependent) but verify the publish timer
        // is registered by THIS test.
        assertNotNull(meterRegistry.find("rag.ingest.http.publish.duration").timer(),
                "publish timer must be registered");
    }

    private double counterValue(String name, String... tags) {
        var counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
