package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.core.model.DocumentVersionMeta;
import io.github.yysf1949.rag.core.port.DocumentVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Phase 20 — Controller-level integration test for {@link DocumentVersionController}.
 *
 * <p>Uses standalone MockMvc setup (no Spring context) to verify the REST
 * surface works correctly with a mocked {@link DocumentVersionService}.
 * This complements the Phase 19 real LLM E2E (which goes through the
 * tool layer, not the controller).</p>
 *
 * <p>Covers all 3 endpoints (7 test cases):</p>
 * <ol>
 *   <li>GET .../docs/{docId} — list versions (populated + empty)</li>
 *   <li>GET .../docs/{docId}/active — get active (present + absent)</li>
 *   <li>POST .../docs/{docId}/publish — publish (new + rollback-via-publish)</li>
 *   <li>Missing X-Tenant-Id header — 400</li>
 * </ol>
 */
@DisplayName("DocumentVersionController REST surface test (Phase 20)")
class DocumentVersionControllerTest {

    MockMvc mvc;
    DocumentVersionService documentVersionService;
    ObjectMapper objectMapper;

    private static final String BASE = "/api/agent/kb-versions/kb1/docs/docA";
    private static final String TENANT = "test-tenant";

    @BeforeEach
    void setup() {
        documentVersionService = mock(DocumentVersionService.class);
        DocumentVersionController controller = new DocumentVersionController(documentVersionService);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice()  // default error handling
                .build();
        objectMapper = new ObjectMapper();
    }

    // ─── 1. GET list versions ──────────────────────────────────────────────

    @Test
    void listVersions_returnsVersions() throws Exception {
        DocumentVersionMeta v1 = new DocumentVersionMeta(
                1L, "docA", DocumentVersionMeta.Status.ACTIVE,
                Instant.now(), Instant.now(), 5, "v1-label");
        DocumentVersionMeta v2 = new DocumentVersionMeta(
                2L, "docA", DocumentVersionMeta.Status.DEPRECATED,
                Instant.now(), null, 3, "v2-label");

        when(documentVersionService.listVersions(TENANT, "kb1", "docA"))
                .thenReturn(List.of(v1, v2));

        mvc.perform(get(BASE)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").value("kb1"))
                .andExpect(jsonPath("$.docId").value("docA"))
                .andExpect(jsonPath("$.versions.length()").value(2))
                .andExpect(jsonPath("$.versions[0].versionId").value(1))
                .andExpect(jsonPath("$.versions[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$.versions[1].versionId").value(2))
                .andExpect(jsonPath("$.versions[1].status").value("DEPRECATED"));
    }

    // ─── 2. GET active version ────────────────────────────────────────────

    @Test
    void getActiveVersion_returnsActive() throws Exception {
        when(documentVersionService.getActiveVersion(TENANT, "kb1", "docA"))
                .thenReturn(Optional.of(3L));

        mvc.perform(get(BASE + "/active")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").value("kb1"))
                .andExpect(jsonPath("$.docId").value("docA"))
                .andExpect(jsonPath("$.activeVersionId").value(3));
    }

    // ─── 3. GET active version — no active ────────────────────────────────

    @Test
    void getActiveVersion_noActive_returnsNull() throws Exception {
        when(documentVersionService.getActiveVersion(TENANT, "kb1", "docA"))
                .thenReturn(Optional.empty());

        mvc.perform(get(BASE + "/active")
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersionId").doesNotExist());
    }

    // ─── 4. POST publish ──────────────────────────────────────────────────

    @Test
    void publish_callsServiceAndReturnsOk() throws Exception {
        DocumentVersionMeta published = new DocumentVersionMeta(
                2L, "docA", DocumentVersionMeta.Status.ACTIVE,
                Instant.now(), Instant.now(), 5, "release");
        when(documentVersionService.publish(TENANT, "kb1", "docA", 2L, "release"))
                .thenReturn(published);

        mvc.perform(post(BASE + "/publish")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\": 2, \"sourceLabel\": \"release\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").value("kb1"))
                .andExpect(jsonPath("$.docId").value("docA"))
                .andExpect(jsonPath("$.versionId").value(2))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(documentVersionService).publish(TENANT, "kb1", "docA", 2L, "release");
    }

    // ─── 5. POST publish (rollback via publish) ───────────────────────────

    @Test
    void publish_rollbackViaPublish_works() throws Exception {
        DocumentVersionMeta rolled = new DocumentVersionMeta(
                1L, "docA", DocumentVersionMeta.Status.ACTIVE,
                Instant.now(), Instant.now(), 3, null);
        when(documentVersionService.publish(TENANT, "kb1", "docA", 1L, null))
                .thenReturn(rolled);

        mvc.perform(post(BASE + "/publish")
                        .header("X-Tenant-Id", TENANT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\": 1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versionId").value(1))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    // ─── 6. Empty list ────────────────────────────────────────────────────

    @Test
    void listVersions_empty_returnsEmptyList() throws Exception {
        when(documentVersionService.listVersions(TENANT, "kb1", "docA"))
                .thenReturn(List.of());

        mvc.perform(get(BASE)
                        .header("X-Tenant-Id", TENANT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.versions.length()").value(0));
    }

    // ─── 7. Missing tenant header ─────────────────────────────────────────

    @Test
    void missingTenantHeader_returns400() throws Exception {
        mvc.perform(get(BASE))
                .andExpect(status().isBadRequest());
    }
}
