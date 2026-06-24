package io.github.yysf1949.rag.agent.gdpr;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GdprDeletionController} 测试 — MockMvc standalone 模式.
 *
 * <p>覆盖: DELETE 端点 + 返回体结构 + 跨租户隔离验证.</p>
 */
class GdprDeletionControllerTest {

    private GdprDeletionPort port;
    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        port = mock(GdprDeletionPort.class);
        mvc = MockMvcBuilders.standaloneSetup(new GdprDeletionController(port))
                .setControllerAdvice(new io.github.yysf1949.rag.agent.web.AgentExceptionHandler())
                .build();
    }

    @Test
    void deleteUserReturnsResult() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("agent_order", 2L);
        counts.put("agent_feedback", 1L);

        GdprDeletionResult result = new GdprDeletionResult(
                "u1", "t1", Instant.now().toEpochMilli(),
                counts, true, List.of()
        );
        when(port.deleteUser("t1", "u1")).thenReturn(result);

        mvc.perform(delete("/api/gdpr/users/u1")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("u1"))
                .andExpect(jsonPath("$.tenantId").value("t1"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.storeCounts.agent_order").value(2))
                .andExpect(jsonPath("$.storeCounts.agent_feedback").value(1));
    }

    @Test
    void deleteUserWithPartialFailureReturns200WithSuccessFalse() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("agent_order", 1L);
        counts.put("chat_memory", 0L);

        GdprDeletionResult result = new GdprDeletionResult(
                "u2", "t1", Instant.now().toEpochMilli(),
                counts, false, List.of("chat_memory: table not found")
        );
        when(port.deleteUser("t1", "u2")).thenReturn(result);

        mvc.perform(delete("/api/gdpr/users/u2")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.errors[0]").value("chat_memory: table not found"));
    }

    @Test
    void deleteUserWithNoDataReturnsZeroCounts() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("agent_order", 0L);

        GdprDeletionResult result = new GdprDeletionResult(
                "nobody", "t1", Instant.now().toEpochMilli(),
                counts, true, List.of()
        );
        when(port.deleteUser("t1", "nobody")).thenReturn(result);

        mvc.perform(delete("/api/gdpr/users/nobody")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("nobody"))
                .andExpect(jsonPath("$.storeCounts.agent_order").value(0));
    }

    @Test
    void deleteUserIdempotentSecondCall() throws Exception {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("agent_order", 0L);
        counts.put("agent_feedback", 0L);

        GdprDeletionResult result = new GdprDeletionResult(
                "u1", "t1", Instant.now().toEpochMilli(),
                counts, true, List.of()
        );
        when(port.deleteUser(anyString(), anyString())).thenReturn(result);

        // Second call — all zeros
        mvc.perform(delete("/api/gdpr/users/u1")
                        .header("X-Tenant-Id", "t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.storeCounts.agent_order").value(0))
                .andExpect(jsonPath("$.storeCounts.agent_feedback").value(0));
    }
}
