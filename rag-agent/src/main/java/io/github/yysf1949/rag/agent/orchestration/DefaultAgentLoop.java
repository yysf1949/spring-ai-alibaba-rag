package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.api.AgentService;
import io.github.yysf1949.rag.agent.exception.AmountLimitExceededException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.agent.handoff.HandoffContext;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认单次循环 — 找到 tool → 过风险门 → 反射调用 → 审计 + metrics 埋点 + handoff 分流。
 *
 * <h2>Phase 10 升级</h2>
 * <ul>
 *   <li>{@link AgentMetrics#recordToolInvocation} 每次调用都埋点</li>
 *   <li>{@link AmountLimitExceededException} 走 {@link HandoffService#handoff} → HANDOFF_REQUIRED</li>
 *   <li>L4 admin 拒绝走 handoff → HANDOFF_REQUIRED</li>
 *   <li>普通 DENIED 返回 DENIED + 审计</li>
 *   <li>幂等回放 REPLAY + {@link AgentMetrics#recordIdempotencyReplay}</li>
 *   <li>反射异常 FAILURE + {@link AgentMetrics#recordErrorExecution}</li>
 * </ul>
 */
@Component
public class DefaultAgentLoop implements AgentLoop, AgentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentLoop.class);

    private final ToolRegistry registry;
    private final RiskGate riskGate;
    private final IdempotencyStore idemStore;
    private final ToolAuditBridge auditBridge;
    private final AgentMetrics metrics;
    private final HandoffService handoffService;
    private final ObjectMapper objectMapper;

    public DefaultAgentLoop(ToolRegistry registry, RiskGate riskGate,
                            IdempotencyStore idemStore, ToolAuditBridge auditBridge,
                            AgentMetrics metrics, HandoffService handoffService,
                            ObjectMapper objectMapper) {
        this.registry = registry;
        this.riskGate = riskGate;
        this.idemStore = idemStore;
        this.auditBridge = auditBridge;
        this.metrics = metrics;
        this.handoffService = handoffService;
        this.objectMapper = objectMapper;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        long start = System.currentTimeMillis();
        AgentOutcome outcome = AgentOutcome.FAILURE;
        Object result = null;
        AgentResponse.HandoffContextPayload handoffPayload = null;

        try {
            // 1. 查工具
            ToolDescriptor desc = registry.get(request.toolName());

            // 2. 金额门控 + 风险门控
            Long amountCents = extractAmountCents(desc, request);
            try {
                riskGate.check(desc, request.identity(), request.idempotencyKey(), amountCents);
            } catch (AmountLimitExceededException e) {
                // AmountLimitExceeded → 走 handoff 流程
                List<String> toolChain = List.of(request.toolName());
                HandoffContext hctx = HandoffContext.forAmountLimit(
                        request.identity(), request.toolName(),
                        e.requestedCents(), e.limitCents(), toolChain);
                handoffService.handoff(hctx);
                handoffPayload = new AgentResponse.HandoffContextPayload(
                        hctx.reason().name(), hctx.channel().name(),
                        hctx.summary(), hctx.toolChainJson());
                outcome = AgentOutcome.HANDOFF_REQUIRED;
                long latency = System.currentTimeMillis() - start;
                metrics.recordToolInvocation(request.toolName(), outcome, latency);
                return new AgentResponse(request.toolName(), outcome, null,
                        "已转人工处理: " + hctx.summary(), latency, handoffPayload);
            } catch (ToolRiskDeniedException e) {
                // L4 admin 拒绝 — 也走 handoff
                if (desc.riskLevel() == RiskLevel.L4_HIGH_RISK) {
                    List<String> toolChain = List.of(request.toolName());
                    HandoffContext hctx = HandoffContext.forInsufficientPrivilege(
                            request.identity(), request.toolName(), toolChain);
                    handoffService.handoff(hctx);
                    handoffPayload = new AgentResponse.HandoffContextPayload(
                            hctx.reason().name(), hctx.channel().name(),
                            hctx.summary(), hctx.toolChainJson());
                    outcome = AgentOutcome.HANDOFF_REQUIRED;
                    long latency = System.currentTimeMillis() - start;
                    metrics.recordToolInvocation(request.toolName(), outcome, latency);
                    return new AgentResponse(request.toolName(), outcome, null,
                            "已转人工处理: 需要 admin 审批", latency, handoffPayload);
                }
                // 普通 DENIED (L2 缺幂等键等) — 记录审计 + 返回 DENIED
                outcome = AgentOutcome.DENIED;
                long latency = System.currentTimeMillis() - start;
                recordAudit(request, desc, "{}", "DENIED", latency);
                metrics.recordToolInvocation(request.toolName(), outcome, latency);
                return new AgentResponse(request.toolName(), outcome, null,
                        e.getMessage(), latency, null);
            }

            // 3. 幂等检查
            if (request.idempotencyKey() != null) {
                var putResult = idemStore.putIfAbsent(request.idempotencyKey(), null);
                if (putResult.isReplay()) {
                    outcome = AgentOutcome.REPLAY;
                    metrics.recordIdempotencyReplay(request.toolName());
                    long latency = System.currentTimeMillis() - start;
                    metrics.recordToolInvocation(request.toolName(), outcome, latency);
                    return new AgentResponse(request.toolName(), outcome, putResult.value(),
                            "(replay) " + safeToJson(putResult.value()), latency, null);
                }
            }

            // 4. 反射执行
            result = invokeWithInjection(desc, request);
            String responseJson = safeToJson(result);

            // 5. 写回幂等结果
            if (request.idempotencyKey() != null) {
                idemStore.replace(request.idempotencyKey(), result);
            }

            outcome = AgentOutcome.SUCCESS;
            long latency = System.currentTimeMillis() - start;
            recordAudit(request, desc, responseJson, "SUCCESS", latency);
            metrics.recordToolInvocation(request.toolName(), outcome, latency);
            return new AgentResponse(request.toolName(), outcome, result, responseJson, latency, null);

        } catch (Exception e) {
            // ToolNotFoundException / 反射调用异常 / 其他意外异常 → FAILURE
            String errorType = e.getClass().getSimpleName();
            long latency = System.currentTimeMillis() - start;
            metrics.recordErrorExecution(request.toolName(), errorType);
            metrics.recordToolInvocation(request.toolName(), AgentOutcome.FAILURE, latency);
            log.error("Tool [{}] execution failed: {}", request.toolName(), e.getMessage(), e);
            return new AgentResponse(request.toolName(), AgentOutcome.FAILURE, null,
                    "Tool execution failed: " + e.getMessage(), latency, null);
        }
    }

    private Object invokeWithInjection(ToolDescriptor desc, AgentRequest request) throws Exception {
        Method m = desc.method();
        Class<?>[] params = m.getParameterTypes();
        List<Object> args = new ArrayList<>(3);
        for (Class<?> p : params) {
            if (p == AgentIdentity.class) {
                args.add(request.identity());
            } else if (p == IdempotencyKey.class) {
                args.add(request.idempotencyKey());
            } else {
                Object payload = request.requestPayload();
                if (payload == null) {
                    throw new IllegalArgumentException("Tool [" + desc.name() + "] request payload is null");
                }
                if (p.isInstance(payload)) {
                    args.add(payload);
                } else {
                    String json = objectMapper.writeValueAsString(payload);
                    args.add(objectMapper.readValue(json, p));
                }
            }
        }
        return m.invoke(desc.bean(), args.toArray());
    }

    private Long extractAmountCents(ToolDescriptor desc, AgentRequest request) {
        Object payload = request.requestPayload();
        if (payload == null) return null;
        try {
            java.lang.reflect.Field amountCentsField = payload.getClass().getDeclaredField("amountCents");
            amountCentsField.setAccessible(true);
            Object v = amountCentsField.get(payload);
            if (v instanceof Long l) return l;
        } catch (NoSuchFieldException | IllegalAccessException ignored) { }
        return null;
    }

    private void recordAudit(AgentRequest request, ToolDescriptor desc,
                             String responseJson, String outcomeStr, long latencyMs) {
        try {
            String requestJson = objectMapper.writeValueAsString(request.requestPayload());
            var ctx = new ToolInvocationContext(
                    request.identity(), desc.name(), requestJson, responseJson,
                    latencyMs, outcomeStr);
            auditBridge.record(ctx);
        } catch (Exception e) {
            log.warn("Audit recording failed for [{}]: {}", desc.name(), e.getMessage());
        }
    }

    private String safeToJson(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        try {
            return objectMapper.writeValueAsString(o);
        } catch (JsonProcessingException e) {
            return o.toString();
        }
    }
}
