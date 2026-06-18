package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.api.ChatReply;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.orchestration.ChatClientService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.util.Set;
import java.util.UUID;

/**
 * Agent HTTP 接入 — POST /api/agent/invoke (Phase 11 ship) + POST /api/agent/chat (Phase 16 新增)
 *
 * <h2>对齐已有 RAG controller 风格</h2>
 * <p>tenantId 走 header（{@code X-Tenant-Id}），body 里的 tenantId 忽略。
 * 跟 {@code RagController} 一致 — 不破坏设计原则 §6 租户硬墙。</p>
 *
 * <h2>Phase 16 双 endpoint 契约</h2>
 * <ul>
 *   <li><b>/invoke</b> — Phase 11 ship, 单 tool 反射调用, 无 LLM, 无流式. 不动.</li>
 *   <li><b>/chat</b> — Phase 16 新增, LLM 自由对话 + 自动选 tool (走 ChatClientService).
 *       <ul>
 *         <li>{@code X-Session-Id} header 决定多轮 (缺失则 UUID)</li>
 *         <li>{@code Accept: text/event-stream} → SSE 流式</li>
 *         <li>默认 {@code application/json} → 一次性 ChatReply</li>
 *         <li>默认 {@link AuthorizationContext#permissive()}, Phase 17 推 stage header 解析</li>
 *       </ul>
 *   </li>
 * </ul>
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent", description = "AI Agent Action Layer invocation endpoints.")
public class AgentController {

    private final AgentService agentService;
    private final ChatClientService chatClientService;

    public AgentController(AgentService agentService,
                           ChatClientService chatClientService) {
        this.agentService = agentService;
        this.chatClientService = chatClientService;
    }

    public record InvokeRequest(
            @NotBlank String userId,
            String sessionId,
            @NotBlank String toolName,
            Object payload,
            String idempotencyToken
    ) { }

    @PostMapping("/invoke")
    @Operation(summary = "调用一个 Agent Tool（编排层单次循环）",
            description = "tenantId 走 X-Tenant-Id header；L2+ 写操作需传 idempotencyToken。")
    public ResponseEntity<AgentResponse> invoke(
            @RequestHeader("X-Tenant-Id") String tenantId,
            @Valid @RequestBody InvokeRequest req,
            HttpServletRequest http) {
        AgentIdentity identity = new AgentIdentity(
                tenantId, req.userId(), req.sessionId(), Set.of("user"));
        IdempotencyKey key = req.idempotencyToken() == null ? null :
                IdempotencyKey.of(tenantId, req.userId(), req.sessionId(), req.toolName(), req.idempotencyToken());
        AgentRequest ar = AgentRequest.of(identity, req.toolName(), req.payload(), key);
        return ResponseEntity.ok(agentService.execute(ar));
    }

    // ============================================================
    // Phase 16 Task 3: /api/agent/chat endpoint — 双模式 (JSON + SSE)
    // ============================================================

    public record ChatRequest(
            @NotBlank String userId,
            @NotBlank String message
    ) { }

    /**
     * LLM 自由对话 + 自动选 tool — 模式由 Accept header 决定.
     *
     * <p>Phase 16: ctx 固定 permissive(). Phase 17 推 {@code X-Agent-Stage} header → ctx stage 解析.</p>
     */
    @PostMapping(value = "/chat", produces = {MediaType.APPLICATION_JSON_VALUE, MediaType.TEXT_EVENT_STREAM_VALUE})
    @Operation(summary = "LLM 自由对话 (Phase 16)",
            description = "走 ChatClientService.chatWithMemory 或 stream. Accept: text/event-stream 触发 SSE.")
    public Object chat(  // 返回类型 Object: JSON 路径返 ResponseEntity<ChatReply>, SSE 路径返 SseEmitter
            @RequestHeader("X-Tenant-Id") String tenantId,
            @RequestHeader(value = "X-Session-Id", required = false) String sessionId,
            @RequestHeader(value = "Accept", required = false) String acceptHeader,
            @Valid @RequestBody ChatRequest req) {

        String conversationId = (sessionId != null && !sessionId.isBlank())
                ? sessionId : UUID.randomUUID().toString();

        // Phase 16: 默认 permissive, Phase 17 再做 stage 解析
        AuthorizationContext ctx = AuthorizationContext.permissive();

        boolean isStream = acceptHeader != null && acceptHeader.contains(MediaType.TEXT_EVENT_STREAM_VALUE);

        if (isStream) {
            // Spring MVC SSE 标准做法: 用 SseEmitter, 把 Flux<String> subscribe 进去逐 token send.
            // 不能直接返回 Flux<String> + text/event-stream — Spring MVC 默认无对应 converter
            // (No converter for FluxConcatArray with preset Content-Type 'text/event-stream').
            // 必须直接返回 SseEmitter 类型 (不能包 ResponseEntity), Spring MVC 才能识别走 SSE 路径.
            SseEmitter emitter = new SseEmitter();
            Flux<String> tokens = chatClientService.stream(req.message(), conversationId, ctx);
            tokens
                    .subscribe(
                            token -> sendSse(emitter, token, "token"),
                            err -> emitter.completeWithError(err),
                            () -> {
                                try {
                                    emitter.send(SseEmitter.event()
                                            .name("done")
                                            .data("{\"conversationId\":\"" + conversationId + "\"}"));
                                    emitter.complete();
                                } catch (Exception e) {
                                    emitter.completeWithError(e);
                                }
                            });
            // 直接返回 emitter, Spring MVC 看到 SseEmitter 返回类型自动用 SSE 序列化
            return emitter;
        }

        ChatReply reply = chatClientService.chatWithMemory(req.message(), conversationId, ctx);
        return ResponseEntity.ok()
                .header("X-Conversation-Id", conversationId)
                .body(reply);
    }

    /**
     * SSE token 发送 helper — 静默吞掉 IOException (客户端断开常见, 不应回流污染主链路).
     */
    private void sendSse(SseEmitter emitter, String data, String eventName) {
        try {
            emitter.send(SseEmitter.event().name(eventName).data(data));
        } catch (Exception ignored) {
            // client disconnected mid-stream; emitter will be cleaned up by container
        }
    }
}