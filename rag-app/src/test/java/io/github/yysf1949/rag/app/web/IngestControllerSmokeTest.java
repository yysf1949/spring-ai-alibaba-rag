package io.github.yysf1949.rag.app.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.app.audit.AuditChannel;
import io.github.yysf1949.rag.core.model.IngestJob;
import io.github.yysf1949.rag.core.model.IngestJobStatus;
import io.github.yysf1949.rag.core.port.IngestService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke for the {@code /api/ingest} HTTP surface — design spec §6.3.
 *
 * <p>Tests run against the real Spring stack but with {@link IngestService}
 * mocked so we don't need a real embedding gateway / vector store. This keeps
 * the controller tests fast and deterministic.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.main.web-application-type=servlet",
        "spring.data.redis.host=nonexistent",
        "spring.data.redis.port=0",
        "spring.ai.openai.api-key=test-key"
})
class IngestControllerSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IngestService ingestService;

    @MockBean
    private AuditChannel auditChannel; // avoid Spring wiring a real SLF4J logger; we don't assert audit in this test

    @Test
    void postIngestReturns202WithPendingJob() throws Exception {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-06-17T10:00:00Z");
        when(ingestService.ingestAsync(any())).thenReturn(
                new IngestJob(jobId, "tenant-A", "kb-prod-001/doc-1", "1",
                        IngestJobStatus.PENDING, 0, 0, 0, 0, now, now, null));

        var body = Map.of(
                "kbId", "kb-prod-001",
                "documentId", "kb-prod-001/doc-1",
                "documentVersion", 5,
                "title", "退款规则",
                "sourceUri", "https://docs.example.com/refund",
                "permissionTags", List.of("ROLE_USER"),
                "sections", List.of(Map.of(
                        "heading", "运费条款",
                        "content", "运费退还规则：商品签收 7 日内可申请运费退款。")));

        mvc.perform(post("/api/ingest")
                        .header("X-Tenant-Id", "tenant-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.tenantId").value("tenant-A"))
                .andExpect(jsonPath("$.documentId").value("kb-prod-001/doc-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void postIngestWithoutTenantHeaderReturns401AsProblemDetail() throws Exception {
        var body = Map.of(
                "kbId", "kb-prod-001",
                "documentId", "doc-1",
                "documentVersion", 1,
                "title", "t",
                "sourceUri", "https://x",
                "sections", List.of(Map.of("heading", "h", "content", "c")));

        mvc.perform(post("/api/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("missing-tenant"));
    }

    @Test
    void postIngestWithMissingFieldsReturns400AsProblemDetail() throws Exception {
        var body = Map.of("kbId", "kb-1");   // missing required fields

        mvc.perform(post("/api/ingest")
                        .header("X-Tenant-Id", "tenant-A")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("validation-failed"))
                .andExpect(jsonPath("$.violations").isArray());
    }

    @Test
    void getIngestJobReturnsJobSnapshot() throws Exception {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-06-17T10:05:00Z");
        when(ingestService.getJob(jobId)).thenReturn(Optional.of(
                new IngestJob(jobId, "tenant-A", "doc-1", "1",
                        IngestJobStatus.READY, 12, 12, 12, 0, now, now, null)));

        mvc.perform(get("/api/ingest/{jobId}", jobId)
                        .header("X-Tenant-Id", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.totalChunks").value(12))
                .andExpect(jsonPath("$.upsertedChunks").value(12));
    }

    @Test
    void getIngestJobForUnknownIdReturns404AsProblemDetail() throws Exception {
        String unknownJobId = UUID.randomUUID().toString();
        when(ingestService.getJob(unknownJobId)).thenReturn(Optional.empty());

        mvc.perform(get("/api/ingest/{jobId}", unknownJobId)
                        .header("X-Tenant-Id", "tenant-A"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("ingest-job-not-found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(unknownJobId)));
    }

    @Test
    void postPublishPromotesReadyJobToPublished() throws Exception {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-06-17T10:10:00Z");
        when(ingestService.publish(jobId)).thenReturn(
                new IngestJob(jobId, "tenant-A", "doc-1", "1",
                        IngestJobStatus.PUBLISHED, 12, 12, 12, 0, now, now, null));

        mvc.perform(post("/api/ingest/{jobId}/publish", jobId)
                        .header("X-Tenant-Id", "tenant-A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.status").value("PUBLISHED"));
    }

    @Test
    void openApiDocumentExposesIngestEndpoint() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/ingest'].post.operationId").exists())
                .andExpect(jsonPath("$.paths['/api/ingest'].post.summary").exists())
                .andExpect(jsonPath("$.paths['/api/ingest'].post.tags[0]").value("Ingest"))
                .andExpect(jsonPath("$.paths['/api/ingest'].post.responses.202").exists())
                .andExpect(jsonPath("$.paths['/api/ingest/{jobId}'].get.operationId").exists())
                .andExpect(jsonPath("$.paths['/api/ingest/{jobId}'].get.responses.404").exists())
                .andExpect(jsonPath("$.paths['/api/ingest/{jobId}/publish'].post.operationId").exists())
                .andReturn();

        // Light extra sanity: the components schema for IngestRequest is registered
        // and lists the expected required fields.
        String json = result.getResponse().getContentAsString();
        assertTrue(json.contains("\"IngestRequest\""), "OpenAPI document should expose IngestRequest schema");
        assertTrue(json.contains("\"documentId\""), "IngestRequest schema should reference documentId");
    }
}
