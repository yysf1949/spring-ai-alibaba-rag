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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end smoke for the {@code POST /api/ingest/multipart} HTTP surface —
 * design spec §6.3, Phase 35 (narrow-scope rebuild v2).
 *
 * <p>Mirrors {@link IngestControllerSmokeTest}'s shape: real Spring stack,
 * {@link IngestService} mocked, {@code X-Tenant-Id} enforced. The two
 * scenarios covered are exactly the contract pins from the Phase 35
 * narrow-scope rebuild task body:</p>
 *
 * <ol>
 *   <li>Happy path — a {@code MockMultipartFile} with
 *       {@code Content-Type: application/pdf} plus a JSON {@code request}
 *       part is accepted; the controller wraps the file as a single-section
 *       {@link io.github.yysf1949.rag.core.model.Document} and dispatches
 *       through {@code ingestService.ingestAsync}, returning {@code 202}
 *       with a PENDING job.</li>
 *   <li>Wrong content type — a non-PDF file part returns
 *       {@code 400 application/problem+json} with
 *       {@code title=unsupported-multipart-content-type}.</li>
 * </ol>
 *
 * <p>Note: the actual {@code application/pdf} byte content is irrelevant for
 * the controller wire-up — the controller never parses the PDF. The
 * pipeline-layer PDF parsing is a separate concern handled by the upstream
 * parser (per the {@link io.github.yysf1949.rag.core.model.Document}
 * docstring). The test only verifies that the controller path accepts the
 * request, builds the marker-body Document, and calls
 * {@code ingestService.ingestAsync}.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
        "spring.main.web-application-type=servlet",
        "spring.rag.redis.enabled=false",
        "spring.data.redis.host=nonexistent",
        "spring.data.redis.port=0",
        "spring.ai.openai.api-key=test-key",
        // Multipart limits — keep defaults reasonable for tests; we only
        // upload 1 KB blobs here.
        "spring.servlet.multipart.max-file-size=10MB",
        "spring.servlet.multipart.max-request-size=10MB"
})
class IngestControllerMultipartSmokeTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private IngestService ingestService;

    @MockBean
    private AuditChannel auditChannel; // avoid Spring wiring a real SLF4J logger; we don't assert audit in this test

    @Test
    void postIngestMultipartWithPdfFileReturns202WithPendingJob() throws Exception {
        String jobId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-06-17T11:00:00Z");
        when(ingestService.ingestAsync(any())).thenReturn(
                new IngestJob(jobId, "tenant-A", "kb-prod-001/doc-1", "1",
                        IngestJobStatus.PENDING, 0, 0, 0, 0, now, now, null));

        // PDF byte content is intentionally a synthetic stub. The controller
        // never reads the bytes — it only uses the file metadata
        // (filename, size, content-type) to build the marker body. Real PDF
        // parsing happens upstream.
        MockMultipartFile pdf = new MockMultipartFile(
                "file",                          // part name matching @RequestPart("file")
                "refund-policy.pdf",             // original filename
                MediaType.APPLICATION_PDF_VALUE, // content type
                "%PDF-1.4\n% synthetic test payload\n".getBytes());

        Map<String, Object> meta = Map.of(
                "kbId", "kb-prod-001",
                "documentId", "kb-prod-001/doc-1",
                "documentVersion", 5,
                "title", "退款规则",
                "sourceUri", "https://docs.example.com/refund",
                "permissionTags", List.of("ROLE_USER"),
                "sections", List.of(Map.of(
                        "heading", "运费条款",
                        "content", "ignored — controller wraps the file as a single section.")));

        // The "request" part must be sent as a separate MockMultipartFile
        // whose content-type is application/json; Spring's @RequestPart binds
        // it via Jackson to IngestRequest.
        MockMultipartFile jsonPart = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(meta));

        mvc.perform(multipart("/api/ingest/multipart")
                        .file(pdf)
                        .file(jsonPart)
                        .header("X-Tenant-Id", "tenant-A")
                        .with(req -> { req.setMethod("POST"); return req; }))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.jobId").value(jobId))
                .andExpect(jsonPath("$.tenantId").value("tenant-A"))
                .andExpect(jsonPath("$.documentId").value("kb-prod-001/doc-1"))
                .andExpect(jsonPath("$.status").value("PENDING"));
    }

    @Test
    void postIngestMultipartWithNonPdfFileReturns400AsProblemDetail() throws Exception {
        // Same JSON metadata — but the file part claims Content-Type
        // text/plain, which the controller rejects as "unsupported".
        MockMultipartFile notAPdf = new MockMultipartFile(
                "file",
                "notes.txt",
                MediaType.TEXT_PLAIN_VALUE,
                "this is not a PDF".getBytes());

        Map<String, Object> meta = Map.of(
                "kbId", "kb-prod-001",
                "documentId", "kb-prod-001/doc-2",
                "documentVersion", 1,
                "title", "t",
                "sourceUri", "https://x",
                "sections", List.of(Map.of("heading", "h", "content", "c")));

        MockMultipartFile jsonPart = new MockMultipartFile(
                "request",
                "",
                MediaType.APPLICATION_JSON_VALUE,
                objectMapper.writeValueAsBytes(meta));

        mvc.perform(multipart("/api/ingest/multipart")
                        .file(notAPdf)
                        .file(jsonPart)
                        .header("X-Tenant-Id", "tenant-A")
                        .with(req -> { req.setMethod("POST"); return req; }))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.title").value("unsupported-multipart-content-type"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.rejectedContentType").value("text/plain"));
    }
}
