package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.KbSearchRequest;
import io.github.yysf1949.rag.agent.builtin.KbSearchResponse;
import io.github.yysf1949.rag.agent.orchestration.ChatClientService;
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
    // Phase 16 Task 3: AgentController 新增 ChatClientService 依赖; @WebMvcTest 不装配 ChatClient,
    // 这里 mock 占位让 spring 注入成功. chat 路径的覆盖由 AgentControllerChatEndpointTest 负责.
    @MockBean private ChatClientService chatClientService;

    @Test
    void invokeReturns200() throws Exception {
        var kbResp = new KbSearchResponse("default", "hi", 0, java.util.List.of());
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
