package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AgentController.class)
class AgentControllerTest {

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper objectMapper;
    @MockBean private AgentService agentService;

    @Test
    void invokeReturns200() throws Exception {
        var kbResp = new KbSearchTool.Response("answer", io.github.yysf1949.rag.core.model.AnswerSource.LLM, java.util.List.of());
        when(agentService.execute(any(AgentRequest.class)))
                .thenReturn(new AgentResponse("kb_search", AgentOutcome.SUCCESS, kbResp, "ok", 12L, null));

        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1",
                "toolName", "kb_search",
                "payload", Map.of("tenantId", "t1", "userId", "u1", "rawText", "hi")));
        mvc.perform(post("/api/agent/invoke")
                        .header("X-Tenant-Id", "t1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.toolName").value("kb_search"))
                .andExpect(jsonPath("$.outcome").value("SUCCESS"));
    }

    @Test
    void missingTenantHeaderReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1", "toolName", "kb_search"));
        mvc.perform(post("/api/agent/invoke")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }
}
