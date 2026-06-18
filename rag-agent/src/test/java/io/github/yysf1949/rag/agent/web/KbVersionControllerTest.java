package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.core.model.KbVersionMeta;
import io.github.yysf1949.rag.core.port.KbVersionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Tests {@link KbVersionController} via {@link MockMvc} with
 * {@link MockMvcBuilders#standaloneSetup} — bypasses Spring context
 * entirely (we only need the controller + a mocked service).
 *
 * <p>Why standalone, not {@code @WebMvcTest}: the {@code @WebMvcTest} slice
 * needs the {@code AgentTestConfiguration} test root which auto-imports
 * {@code AgentController}, dragging in {@code AgentService} and
 * {@code ChatClientService} dependencies. For a single-controller test
 * we don't need the Spring boot context at all.</p>
 *
 * <p>Tenant id is provided via the {@code X-Tenant-Id} header (consistent
 * with the rest of the agent endpoints) — body {@code tenantId} is ignored.</p>
 */
class KbVersionControllerTest {

    private KbVersionService kbVersionService;
    private MockMvc mvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        kbVersionService = mock(KbVersionService.class);
        mvc = MockMvcBuilders.standaloneSetup(new KbVersionController(kbVersionService))
                .setControllerAdvice()
                .build();
    }

    @Test
    void listReturnsVersionsNewestFirst() throws Exception {
        when(kbVersionService.listVersions("t1", "kb1")).thenReturn(List.of(
                new KbVersionMeta(3, KbVersionMeta.Status.STAGING, Instant.EPOCH, null, 0, null),
                new KbVersionMeta(2, KbVersionMeta.Status.ACTIVE, Instant.EPOCH, Instant.EPOCH, 10, "v2"),
                new KbVersionMeta(1, KbVersionMeta.Status.DEPRECATED, Instant.EPOCH, Instant.EPOCH, 5, "v1")
        ));

        mvc.perform(get("/api/agent/kb-versions/kb1")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").value("kb1"))
                .andExpect(jsonPath("$.versions.length()").value(3))
                .andExpect(jsonPath("$.versions[0].versionId").value(3))
                .andExpect(jsonPath("$.versions[1].status").value("ACTIVE"));
    }

    @Test
    void activeReturnsVersionIdWhenPresent() throws Exception {
        when(kbVersionService.getActiveVersion("t1", "kb1")).thenReturn(Optional.of(7L));

        mvc.perform(get("/api/agent/kb-versions/kb1/active")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").value("kb1"))
                .andExpect(jsonPath("$.activeVersionId").value(7));
    }

    @Test
    void activeReturnsNullWhenNoActive() throws Exception {
        when(kbVersionService.getActiveVersion("t1", "kb1")).thenReturn(Optional.empty());

        mvc.perform(get("/api/agent/kb-versions/kb1/active")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVersionId").doesNotExist());
    }

    @Test
    void publishCallsServiceAndReturnsAck() throws Exception {
        mvc.perform(post("/api/agent/kb-versions/kb1/publish")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"versionId\": 5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kbId").value("kb1"))
                .andExpect(jsonPath("$.versionId").value(5))
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        verify(kbVersionService).publish("t1", "kb1", 5L);
    }

    @Test
    void publishWithMissingVersionIdReturns400() throws Exception {
        mvc.perform(post("/api/agent/kb-versions/kb1/publish")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }
}