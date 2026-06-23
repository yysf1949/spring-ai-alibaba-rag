package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.api.ChatReply;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.orchestration.ChatClientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 16 Task 4b: AgentController /api/agent/chat endpoint 4 用例.
 *
 * <h2>覆盖点 (Plan §2.7 #4-7)</h2>
 * <ul>
 *   <li>#4 chat_json_returnsChatReply — 默认 JSON, 返回 ChatReply</li>
 *   <li>#5 chat_sse_returnsFluxOfServerSentEvents — Accept: text/event-stream 触发 SSE</li>
 *   <li>#6 chat_missingTenantHeaderReturns400 — 旧契约保留, X-Tenant-Id 缺则 400</li>
 *   <li>#7 chat_sessionIdHeader_usesAsConversationId — X-Session-Id 透传到 ChatClientService</li>
 * </ul>
 */
@DisplayName("AgentController /api/agent/chat endpoint test")
@WebMvcTest(AgentController.class)
class AgentControllerChatEndpointTest {

    @Autowired private MockMvc mvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockBean private AgentService agentService;
    @MockBean private ChatClientService chatClientService;

    /**
     * Plan #4: 默认 JSON 模式.
     */
    @Test
    @DisplayName("/chat 默认 Accept=application/json → ChatReply JSON")
    void chat_json_returnsChatReply() throws Exception {
        when(chatClientService.chatWithMemory(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(new ChatReply("您最近的订单是 ORD-2024-1234", "sess-001"));

        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1",
                "message", "查我最近的订单"));

        mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("X-Session-Id", "sess-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("您最近的订单是 ORD-2024-1234"))
                .andExpect(jsonPath("$.conversationId").value("sess-001"))
                .andExpect(header().string("X-Conversation-Id", "sess-001"));
    }

    /**
     * Plan #5: SSE 流式. 验证 Accept: text/event-stream 触发 SSE 响应.
     * <p>Flux chunk 通过 SSE 协议被 Spring MVC 序列化为 {@code data: <token>} 行.</p>
     */
    @Test
    @DisplayName("/chat Accept=text/event-stream → SSE 流式 (含 done event)")
    void chat_sse_returnsFluxOfServerSentEvents() throws Exception {
        when(chatClientService.stream(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(Flux.just("您", "最近", "的", "订单"));

        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1",
                "message", "查我最近的订单"));

        String sseBody = mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("X-Session-Id", "sess-sse-1")
                        .header("Accept", "text/event-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // SSE 协议: data: <token>  每行一个; 最后一行 event: done (含 conversationId, 替代 X-Conversation-Id header)
        // 注: SSE 路径直接返回 SseEmitter, 不像 JSON 路径能 ResponseEntity 加 header;
        //     conversationId 通过 done event 的 data 字段透传给客户端, 语义一致.
        assertThat(sseBody).contains("data:您")
                .contains("data:最近")
                .contains("data:的")
                .contains("data:订单")
                .contains("event:done")
                .contains("\"conversationId\":\"sess-sse-1\"");
    }

    /**
     * Plan #6: 旧契约保留 — X-Tenant-Id 缺失 → 400.
     */
    @Test
    @DisplayName("/chat 缺 X-Tenant-Id → 400 Bad Request")
    void chat_missingTenantHeaderReturns400() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1",
                "message", "hi"));

        mvc.perform(post("/api/agent/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    /**
     * Plan #7: X-Session-Id 透传到 ChatClientService.chatWithMemory 的 conversationId 参数.
     */
    @Test
    @DisplayName("/chat X-Session-Id → 透传到 ChatClientService.conversationId 参数")
    void chat_sessionIdHeader_usesAsConversationId() throws Exception {
        when(chatClientService.chatWithMemory(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(new ChatReply("ok", "sess-from-header"));

        String body = objectMapper.writeValueAsString(Map.of(
                "userId", "u1",
                "message", "hi"));

        mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("X-Session-Id", "sess-from-header")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        // 验证 ChatClientService.chatWithMemory 第二个参数 = "sess-from-header"
        ArgumentCaptor<String> convIdCaptor = ArgumentCaptor.forClass(String.class);
        verify(chatClientService).chatWithMemory(eq("hi"), convIdCaptor.capture(), any(AuthorizationContext.class));
        assertThat(convIdCaptor.getValue()).isEqualTo("sess-from-header");
    }
}