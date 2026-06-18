package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

/**
 * Agent HTTP 接入 — POST /api/agent/invoke
 *
 * <h2>对齐已有 RAG controller 风格</h2>
 * <p>tenantId 走 header（{@code X-Tenant-Id}），body 里的 tenantId 忽略。
 * 跟 {@code RagController} 一致 — 不破坏设计原则 §6 租户硬墙。</p>
 */
@RestController
@RequestMapping("/api/agent")
@Tag(name = "Agent", description = "AI Agent Action Layer invocation endpoints.")
public class AgentController {

    private final AgentService agentService;

    public AgentController(AgentService agentService) {
        this.agentService = agentService;
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
}
