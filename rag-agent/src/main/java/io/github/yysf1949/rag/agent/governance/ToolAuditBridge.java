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
 * <h2>为什么走 LlmAuditHook 而不是新写一套</h2>
 * <ul>
 *   <li>现有 audit appender（90 天 RollingFile）+ Kafka 出口直接复用</li>
 *   <li>对齐设计原则 §11 spec 优先 — 不要造平行管道</li>
 *   <li>治理层跟 LLM 调用的 audit 都进同一个通道，方便合规检索</li>
 * </ul>
 *
 * <h2>字段映射</h2>
 * <ul>
 *   <li>{@code queryHash} ← SHA-256(requestJson)</li>
 *   <li>{@code modelId}   ← 固定 {@code "agent:<toolName>"}</li>
 *   <li>{@code promptTemplate} ← 固定 {@code "agent-tool-call"}</li>
 *   <li>{@code promptBody} ← 工具名 + 请求 JSON</li>
 *   <li>{@code completion} ← 响应 JSON + outcome 标签</li>
 *   <li>{@code outcome}   ← SUCCESS / FAILURE / DENIED</li>
 * </ul>
 *
 * <h2>Phase 13a 改造</h2>
 * <p>{@link SensitiveDataMasker} 在写 audit 前对 requestJson / responseJson / promptBody
 * 做脱敏（身份证 / 银行卡 / 手机号 / 邮箱 / 地址），让审计仍可追溯行为但不再含明文 PII。</p>
 */
@Component
public class ToolAuditBridge {

    private static final Logger log = LoggerFactory.getLogger(ToolAuditBridge.class);
    private static final String MODEL_PREFIX = "agent:";

    private final LlmAuditHook hook;
    private final SensitiveDataMasker masker;

    public ToolAuditBridge(LlmAuditHook hook) {
        this(hook, new SensitiveDataMasker());
    }

    public ToolAuditBridge(LlmAuditHook hook, SensitiveDataMasker masker) {
        this.hook = hook == null ? LlmAuditHook.NOOP : hook;
        this.masker = masker == null ? new SensitiveDataMasker() : masker;
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
        } catch (Exception e) {
            // LlmAuditHook contract: never throws. Absorb errors per spec §21.
            log.warn("ToolAuditBridge failed to record audit for tool [{}]: {}",
                    ctx.toolName(), e.getMessage());
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
