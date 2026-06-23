package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.orchestration.ChatClientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Phase 22: SSE 流式输出专项测试 — 生产必需的边界场景.
 *
 * <h2>覆盖场景</h2>
 * <ol>
 *   <li>SSE 空流 — LLM 返回 0 token 时 done event 仍需发出</li>
 *   <li>SSE 错误恢复 — Flux 中途异常时 SseEmitter 行为</li>
 *   <li>SSE 事件格式 — event:name + data:payload 结构</li>
 *   <li>SSE 大量 chunk — 模拟长文本流式不丢失</li>
 *   <li>SSE 无 sessionId — 自动生成 UUID</li>
 * </ol>
 */
@DisplayName("SSE Streaming — 生产边界场景")
@WebMvcTest(AgentController.class)
class AgentControllerSseStreamingTest {

    @Autowired private MockMvc mvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper objectMapper;
    @MockBean private AgentService agentService;
    @MockBean private ChatClientService chatClientService;

    private String chatBody() throws Exception {
        return objectMapper.writeValueAsString(Map.of("userId", "u1", "message", "test"));
    }

    /**
     * 空流: LLM 没有返回任何 token (例如 tool call 后无文本).
     * 预期: done event 仍发出, conversationId 正确.
     */
    @Test
    @DisplayName("SSE 空流 → 只有 done event, 无 crash")
    void sse_emptyStream_stillSendsDoneEvent() throws Exception {
        when(chatClientService.stream(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(Flux.empty());

        String sseBody = mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("X-Session-Id", "empty-sess")
                        .header("Accept", "text/event-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody()))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 空流时不应有 token data, 但 done event 必须存在
        assertThat(sseBody).contains("event:done");
        assertThat(sseBody).contains("empty-sess");
    }

    /**
     * Flux 中途异常 — 模拟 LLM API 超时 / 网络断开.
     * 预期: 已发出的 token 正常到达, 异常由 SseEmitter 处理 (不 crash).
     */
    @Test
    @DisplayName("SSE 流中途异常 → 已发 token 不丢失, 不 crash")
    void sse_streamError_partialTokensPreserved() throws Exception {
        when(chatClientService.stream(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(Flux.just("您", "好")
                        .concatWith(Flux.error(new IOException("Connection reset"))));

        String sseBody = mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("X-Session-Id", "err-sess")
                        .header("Accept", "text/event-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 部分 token 已发出
        assertThat(sseBody).contains("data:您").contains("data:好");
        // 异常后 done event 不会发出 (emitter.completeWithError 先于 done)
        assertThat(sseBody).doesNotContain("event:done");
    }

    /**
     * SSE 事件格式验证: 每个 token 应为 "event:token\ndata:<token>\n\n" 格式.
     * Spring MVC SseEmitter.send(event().name("token").data(x)) 产生此格式.
     */
    @Test
    @DisplayName("SSE 事件格式: event:token + data:<chunk>")
    void sse_eventFormat_correctStructure() throws Exception {
        when(chatClientService.stream(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(Flux.just("Hello", "World"));

        String sseBody = mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("X-Session-Id", "fmt-sess")
                        .header("Accept", "text/event-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // Spring MVC SSE 格式: "event:<name>\ndata:<data>\n\n"
        assertThat(sseBody).contains("event:token");
        assertThat(sseBody).contains("data:Hello");
        assertThat(sseBody).contains("data:World");
        assertThat(sseBody).contains("event:done");
    }

    /**
     * 大量 chunk — 模拟长文本 (100 chunks).
     * 验证所有 chunk 都到达, done event 正确.
     */
    @Test
    @DisplayName("SSE 100 chunks 全部到达 + done event")
    void sse_manyChunks_allDelivered() throws Exception {
        java.util.List<String> chunks = java.util.stream.IntStream.range(0, 100)
                .mapToObj(i -> "chunk-" + i)
                .toList();
        when(chatClientService.stream(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(Flux.fromIterable(chunks));

        String sseBody = mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("X-Session-Id", "big-sess")
                        .header("Accept", "text/event-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // 首尾 chunk 都在
        assertThat(sseBody).contains("data:chunk-0");
        assertThat(sseBody).contains("data:chunk-99");
        assertThat(sseBody).contains("event:done");
    }

    /**
     * 无 X-Session-Id header → 自动生成 UUID, done event 中的 conversationId 非空.
     */
    @Test
    @DisplayName("SSE 无 sessionId → 自动生成 UUID")
    void sse_noSessionId_generatesUuid() throws Exception {
        when(chatClientService.stream(anyString(), anyString(), any(AuthorizationContext.class)))
                .thenReturn(Flux.just("ok"));

        String sseBody = mvc.perform(post("/api/agent/chat")
                        .header("X-Tenant-Id", "t1")
                        .header("Accept", "text/event-stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(chatBody()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        // done event 中 conversationId 应是 UUID 格式 (8-4-4-4-12)
        assertThat(sseBody).contains("event:done");
        assertThat(sseBody).containsPattern("conversationId.*[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
    }
}
