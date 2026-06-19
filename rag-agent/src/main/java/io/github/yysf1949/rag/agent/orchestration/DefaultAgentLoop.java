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
import io.github.yysf1949.rag.agent.exception.HandoffRequiredException;
import io.github.yysf1949.rag.agent.exception.ToolRiskDeniedException;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.TenantRateLimitedException;
import io.github.yysf1949.rag.agent.governance.TenantRateLimiter;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.agent.handoff.HandoffContext;
import io.github.yysf1949.rag.agent.handoff.HandoffService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

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
 *
 * <h2>Phase 13a 升级</h2>
 * <ul>
 *   <li>租户级 QPS 限流（{@link TenantRateLimiter}）防单租户霸占后端</li>
 * </ul>
 *
 * <h2>Phase 13b M6 升级</h2>
 * <ul>
 *   <li>{@link HandoffRequiredException}（业务规则命中）→ 自动走 handoff 分流，
 *       Context 含完整 matchedRules + riskNote（文章"前置工作证据"原话）</li>
 * </ul>
 *
 * <h2>调试模式</h2>
 * <p>通过 {@link #setDebugMode(DebugMode)} 开启调试追踪，可在关键决策点
 * （INTERPRET / SELECT_TOOLS / EXECUTE / VERIFY）记录 {@link AgentLoopDebugEvent}，
 * 方便开发时观察每一步的决策过程。</p>
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
    private final TenantRateLimiter tenantRateLimiter;
    private final ObjectMapper objectMapper;

    // ── 调试/追踪模式 ────────────────────────────────────────

    private DebugMode debugMode = DebugMode.OFF;
    private final List<AgentLoopDebugEvent> debugTrace = new ArrayList<>();
    private final AtomicInteger debugStepCounter = new AtomicInteger(0);
    private final AgentLoopTracer tracer;

    /**
     * 完整构造 — 含租户级限流。
     */
    public DefaultAgentLoop(ToolRegistry registry, RiskGate riskGate,
                            IdempotencyStore idemStore, ToolAuditBridge auditBridge,
                            AgentMetrics metrics, HandoffService handoffService,
                            TenantRateLimiter tenantRateLimiter,
                            ObjectMapper objectMapper) {
        this(registry, riskGate, idemStore, auditBridge, metrics, handoffService,
                tenantRateLimiter, objectMapper, false);
    }

    /**
     * 完整构造 — 含租户级限流 + 追踪器开关。
     */
    public DefaultAgentLoop(ToolRegistry registry, RiskGate riskGate,
                            IdempotencyStore idemStore, ToolAuditBridge auditBridge,
                            AgentMetrics metrics, HandoffService handoffService,
                            TenantRateLimiter tenantRateLimiter,
                            ObjectMapper objectMapper,
                            boolean tracerEnabled) {
        this.registry = registry;
        this.riskGate = riskGate;
        this.idemStore = idemStore;
        this.auditBridge = auditBridge;
        this.metrics = metrics;
        this.handoffService = handoffService;
        this.tenantRateLimiter = tenantRateLimiter == null
                ? new TenantRateLimiter() : tenantRateLimiter;
        this.objectMapper = objectMapper;
        this.tracer = new AgentLoopTracer(tracerEnabled);
    }

    /**
     * 向后兼容构造 — 未注入 TenantRateLimiter 时,内部用默认实例（无租户隔离风险，因为单租户场景）。
     */
    public DefaultAgentLoop(ToolRegistry registry, RiskGate riskGate,
                            IdempotencyStore idemStore, ToolAuditBridge auditBridge,
                            AgentMetrics metrics, HandoffService handoffService,
                            ObjectMapper objectMapper) {
        this(registry, riskGate, idemStore, auditBridge, metrics, handoffService,
                null, objectMapper, false);
    }

    // ── 调试模式 API ─────────────────────────────────────────

    /**
     * 设置调试模式。
     *
     * @param mode 调试模式（OFF / RECORD / VERBOSE）
     */
    public void setDebugMode(DebugMode mode) {
        this.debugMode = mode == null ? DebugMode.OFF : mode;
    }

    /**
     * 返回当前调试模式。
     */
    public DebugMode getDebugMode() {
        return debugMode;
    }

    /**
     * 返回完整调试追踪（不可变视图）。
     * 仅在 RECORD 或 VERBOSE 模式下有内容。
     */
    public List<AgentLoopDebugEvent> getDebugTrace() {
        return Collections.unmodifiableList(new ArrayList<>(debugTrace));
    }

    /**
     * 清空调试追踪记录并重置步骤计数器。
     */
    public void clearDebugTrace() {
        debugTrace.clear();
        debugStepCounter.set(0);
    }

    // ── 调试事件记录 ─────────────────────────────────────────

    private void recordDebugEvent(AgentLoopDebugEvent.Phase phase,
                                  String toolName, RiskLevel riskLevel,
                                  String toolArgs, String toolResult,
                                  String policyDecision, String llmResponse) {
        if (debugMode == DebugMode.OFF) return;

        int step = debugStepCounter.incrementAndGet();
        AgentLoopDebugEvent event = new AgentLoopDebugEvent(
                step, System.currentTimeMillis(), phase,
                toolName, riskLevel, toolArgs, toolResult,
                policyDecision, llmResponse);
        debugTrace.add(event);

        if (debugMode == DebugMode.VERBOSE) {
            System.err.printf("[DEBUG-TRACE] step=%d phase=%s tool=%s risk=%s policy=%s%n",
                    step, phase, toolName, riskLevel, policyDecision);
            if (toolArgs != null) {
                System.err.printf("[DEBUG-TRACE]   toolArgs=%s%n", toolArgs);
            }
            if (toolResult != null) {
                System.err.printf("[DEBUG-TRACE]   toolResult=%s%n",
                    toolResult);
            }
        }
    }

    // ── 执行入口 ─────────────────────────────────────────────

    /**
     * 返回追踪器实例（测试用）。
     */
    public AgentLoopTracer getTracer() {
        return tracer;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        long start = System.currentTimeMillis();
        // Phase 13a: 租户级 QPS 限流 — 防单租户霸占后端
        try {
            return tenantRateLimiter.execute(request.identity().tenantId(), () -> doExecute(request, start));
        } catch (TenantRateLimitedException e) {
            log.warn("Tenant rate limited: {}", e.getMessage());
            metrics.recordErrorExecution(request.toolName(), "TenantRateLimited");
            long latency = System.currentTimeMillis() - start;
            return new AgentResponse(request.toolName(), AgentOutcome.FAILURE, null,
                    e.getMessage(), latency, null);
        }
    }

    private AgentResponse doExecute(AgentRequest request, long start) {
        AgentOutcome outcome = AgentOutcome.FAILURE;
        Object result = null;
        AgentResponse.HandoffContextPayload handoffPayload = null;

        // ── INTERPRET: 接收请求，解析意图 ──
        String requestJson = safeToJson(request.requestPayload());
        recordDebugEvent(
                AgentLoopDebugEvent.Phase.INTERPRET,
                request.toolName(), null, requestJson, null,
                null, request.toolName());

        try {
            // 1. 查工具
            // ── SELECT_TOOLS: 从注册表中查找工具 ──
            ToolDescriptor desc = registry.get(request.toolName());
            // ── 追踪: 工具选择 ──
            List<String> candidateNames = registry.listNames();
            tracer.logToolSelection(candidateNames, List.of(desc.name()));
            recordDebugEvent(
                    AgentLoopDebugEvent.Phase.SELECT_TOOLS,
                    desc.name(), desc.riskLevel(), requestJson, null,
                    null, null);

            // 2. 金额门控 + 风险门控
            Long amountCents = extractAmountCents(desc, request);
            try {
                riskGate.check(desc, request.identity(), request.idempotencyKey(), amountCents);
                // ── VERIFY: 风险门控通过 ──
                tracer.logRiskGate(desc.name(), desc.riskLevel().name(), "ALLOW", null);
                recordDebugEvent(
                        AgentLoopDebugEvent.Phase.VERIFY,
                        desc.name(), desc.riskLevel(), requestJson, null,
                        "ALLOW", null);
            } catch (AmountLimitExceededException e) {
                // AmountLimitExceeded → 走 handoff 流程
                tracer.logRiskGate(desc.name(), desc.riskLevel().name(), "HANDOFF",
                        "Amount limit exceeded: requested=" + e.requestedCents() + " limit=" + e.limitCents());
                recordDebugEvent(
                        AgentLoopDebugEvent.Phase.VERIFY,
                        desc.name(), desc.riskLevel(), requestJson, null,
                        "DENY", null);

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
                // ── VERIFY: 风险门控拒绝 ──
                tracer.logRiskGate(desc.name(), desc.riskLevel().name(), "DENY", e.getMessage());
                recordDebugEvent(
                        AgentLoopDebugEvent.Phase.VERIFY,
                        desc.name(), desc.riskLevel(), requestJson, null,
                        "DENY", null);

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
                    // ── 追踪: 幂等回放 ──
                    tracer.logIdempotency(request.idempotencyKey().toString(), true);
                    outcome = AgentOutcome.REPLAY;
                    metrics.recordIdempotencyReplay(request.toolName());
                    long latency = System.currentTimeMillis() - start;
                    metrics.recordToolInvocation(request.toolName(), outcome, latency);
                    return new AgentResponse(request.toolName(), outcome, putResult.value(),
                            "(replay) " + safeToJson(putResult.value()), latency, null);
                } else {
                    // ── 追踪: 幂等首次执行 ──
                    tracer.logIdempotency(request.idempotencyKey().toString(), false);
                }
            }

            // 4. 反射执行 — 业务规则命中也走 handoff（Phase 13b M6）
            // ── EXECUTE: 调用工具 ──
            try {
                result = invokeWithInjection(desc, request);
                // ── 追踪: 工具执行成功 ──
                long execDuration = System.currentTimeMillis() - start;
                tracer.logExecution(desc.name(), safeToJson(request.requestPayload()),
                        safeToJson(result), execDuration, "SUCCESS");
            } catch (HandoffRequiredException e) {
                // ── 追踪: 工具执行触发 handoff ──
                long execDuration = System.currentTimeMillis() - start;
                tracer.logExecution(desc.name(), safeToJson(request.requestPayload()),
                        null, execDuration, "HANDOFF");
                recordDebugEvent(
                        AgentLoopDebugEvent.Phase.EXECUTE,
                        desc.name(), desc.riskLevel(), requestJson,
                        safeToJson(null), "HANDOFF", null);

                List<String> toolChain = List.of(request.toolName());
                HandoffContext hctx = HandoffContext.forBusinessRule(
                        request.identity(), e.toolName(), e.reason(),
                        e.matchedRules(), e.riskNote(), toolChain);
                handoffService.handoff(hctx);
                handoffPayload = new AgentResponse.HandoffContextPayload(
                        hctx.reason().name(), hctx.channel().name(),
                        hctx.summary(), hctx.toolChainJson());
                outcome = AgentOutcome.HANDOFF_REQUIRED;
                long latency = System.currentTimeMillis() - start;
                metrics.recordToolInvocation(request.toolName(), outcome, latency);
                return new AgentResponse(request.toolName(), outcome, null,
                        "已转人工处理: 业务规则 [" + e.reason() + "] 命中", latency, handoffPayload);
            }
            String responseJson = safeToJson(result);

            // ── EXECUTE: 记录执行结果 ──
            recordDebugEvent(
                    AgentLoopDebugEvent.Phase.EXECUTE,
                    desc.name(), desc.riskLevel(), requestJson,
                    responseJson, "ALLOW", null);

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
        try {
            return m.invoke(desc.bean(), args.toArray());
        } catch (java.lang.reflect.InvocationTargetException ite) {
            // Phase 13b M6: unwrap — 让 HandoffRequiredException 等业务异常正常向上抛,
            // 不要被 InvocationTargetException 吞掉,否则编排层 catch 抓不到。
            Throwable cause = ite.getCause();
            if (cause instanceof RuntimeException re) {
                throw re;
            }
            if (cause instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(cause);
        }
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
            String reqJson = objectMapper.writeValueAsString(request.requestPayload());
            var ctx = new ToolInvocationContext(
                    request.identity(), desc.name(), reqJson, responseJson,
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
