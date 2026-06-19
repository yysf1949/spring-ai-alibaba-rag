package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.core.port.LlmAuditHook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 工具调用审计桥接 — 复用 {@code rag-core} 已有的
 * {@link LlmAuditHook} 通道（spec §21，rag-app AuditChannel 实现）。
 *
 * <h2>Phase 14 增强：业务指标记录</h2>
 * <p>在审计记录之外，根据工具调用结果同步记录业务指标：
 * FAILURE → recordBusinessError, HANDOFF_REQUIRED → recordHandoffQuality,
 * 确认令牌被拒绝 → recordConfirmation(tool, false)。</p>
 */
@Component
public class ToolAuditBridge {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditBridge.class);
    private static final String MODEL_PREFIX = "agent:";

    private final LlmAuditHook hook;
    private final SensitiveDataMasker masker;
    private final AgentMetrics agentMetrics;

    public ToolAuditBridge(LlmAuditHook hook) {
        this(hook, new SensitiveDataMasker(), null);
    }

    public ToolAuditBridge(LlmAuditHook hook, SensitiveDataMasker masker) {
        this(hook, masker, null);
    }

    public ToolAuditBridge(LlmAuditHook hook, SensitiveDataMasker masker, AgentMetrics agentMetrics) {
        this.hook = hook == null ? LlmAuditHook.NOOP : hook;
        this.masker = masker == null ? new SensitiveDataMasker() : masker;
        this.agentMetrics = agentMetrics;
    }

    public void record(ToolInvocationContext ctx) {
        try {
            String requestJson = ctx.requestJson() == null ? "" : ctx.requestJson();
            String responseJson = ctx.responseJson() == null ? "" : ctx.responseJson();
            String maskedRequest = masker.mask(requestJson);
            String maskedResponse = masker.mask(responseJson);
            String queryHash = sha256(maskedRequest);
            String modelId = MODEL_PREFIX + ctx.toolName();
            String traceTag = TraceContext.current() == null ? "" : " traceId=" + TraceContext.current();
            String promptBody = "tool=" + ctx.toolName() + " request=" + maskedRequest + traceTag;
            String completion = maskedResponse + " outcome=" + ctx.outcome();
            hook.onLlmCall(
                    ctx.identity().tenantId(),
                    ctx.identity().userId(),
                    ctx.identity().sessionId(),
                    queryHash,
                    modelId,
                    "agent-tool-call",
                    promptBody,
                    completion,
                    ctx.latencyMs(),
                    ctx.outcome());

            // 业务指标增强 — 区分治理层错误 vs 业务错误
            recordBusinessMetrics(ctx);

        } catch (Exception e) {
            // LlmAuditHook contract: never throws. Absorb errors per spec §21.
            log.warn("ToolAuditBridge failed to record audit for tool [{}]: {}",
                    ctx.toolName(), e.getMessage());
        }
    }

    /**
     * 根据工具调用结果记录业务指标。
     * <ul>
     *   <li>FAILURE → recordBusinessError（业务执行错误）</li>
     *   <li>HANDOFF_REQUIRED → recordHandoffQuality（转人工质量）</li>
     * </ul>
     */
    private void recordBusinessMetrics(ToolInvocationContext ctx) {
        if (agentMetrics == null) return;

        String outcome = ctx.outcome();
        if ("FAILURE".equals(outcome)) {
            agentMetrics.recordBusinessError(ctx.toolName(), "TOOL_FAILURE");
        } else if ("HANDOFF_REQUIRED".equals(outcome)) {
            // 默认有上下文（请求 JSON 不为空即认为有上下文）
            boolean hasContext = ctx.requestJson() != null && !ctx.requestJson().isEmpty();
            agentMetrics.recordHandoffQuality(hasContext, "HANDOFF_REQUIRED");
        }
    }

    /**
     * 记录用户确认拒绝 — 调用方在确认令牌被拒绝时调用此方法。
     */
    public void recordConfirmationRejected(String toolName) {
        if (agentMetrics != null) {
            agentMetrics.recordConfirmation(toolName, false);
        }
    }

    /**
     * 记录用户确认接受 — 调用方在确认令牌被接受时调用此方法。
     */
    public void recordConfirmationAccepted(String toolName) {
        if (agentMetrics != null) {
            agentMetrics.recordConfirmation(toolName, true);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return "unavailable";
        }
    }
}
