package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.AgentResponse;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.RiskGate;
import io.github.yysf1949.rag.agent.governance.ToolAuditBridge;
import io.github.yysf1949.rag.agent.governance.ToolInvocationContext;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import io.github.yysf1949.rag.agent.api.AgentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 默认单次循环 — 找到 tool → 过风险门 → 反射调用 → 审计。
 *
 * <h2>调用约定</h2>
 * <p>工具方法允许 1-3 个参数，按位置传入：</p>
 * <ol>
 *   <li>参数 0: {@code AgentIdentity}（可选，编排层注入）</li>
 *   <li>参数 1: {@code IdempotencyKey}（可选，写操作必传）</li>
 *   <li>参数 2: 业务 DTO（必传）</li>
 * </ol>
 */
@Component
public class DefaultAgentLoop implements AgentLoop, AgentService {

    private static final Logger log = LoggerFactory.getLogger(DefaultAgentLoop.class);

    private final ToolRegistry registry;
    private final RiskGate riskGate;
    private final IdempotencyStore idempotencyStore;
    private final ToolAuditBridge auditBridge;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public DefaultAgentLoop(ToolRegistry registry, RiskGate riskGate,
                            IdempotencyStore idempotencyStore, ToolAuditBridge auditBridge) {
        this.registry = registry;
        this.riskGate = riskGate;
        this.idempotencyStore = idempotencyStore;
        this.auditBridge = auditBridge;
    }

    @Override
    public AgentResponse execute(AgentRequest request) {
        long t0 = System.currentTimeMillis();
        ToolDescriptor desc = registry.get(request.toolName());
        try {
            // 风险门控
            riskGate.check(desc, request.identity(), request.idempotencyKey());
        } catch (RuntimeException denied) {
            // 拒绝也写审计
            auditBridge.record(new ToolInvocationContext(
                    request.identity(), request.toolName(),
                    safeToJson(request.requestPayload()),
                    denied.getMessage(),
                    System.currentTimeMillis() - t0, "DENIED"));
            throw denied;
        }

        // 反射调用（按参数类型注入 identity / idemKey / 业务 DTO）
        Object result;
        try {
            result = invokeWithInjection(desc, request);
        } catch (Exception e) {
            long latency = System.currentTimeMillis() - t0;
            auditBridge.record(new ToolInvocationContext(
                    request.identity(), request.toolName(),
                    safeToJson(request.requestPayload()),
                    e.getMessage() == null ? "" : e.getMessage(),
                    latency, "FAILURE"));
            throw new RuntimeException("Tool [" + request.toolName() + "] execution failed: " + e.getMessage(), e);
        }

        long latency = System.currentTimeMillis() - t0;
        String resultJson = safeToJson(result);
        auditBridge.record(new ToolInvocationContext(
                request.identity(), request.toolName(),
                safeToJson(request.requestPayload()),
                resultJson,
                latency, "SUCCESS"));

        log.info("Agent tool [{}] completed outcome=SUCCESS latency={}ms",
                request.toolName(), latency);
        return new AgentResponse(request.toolName(), AgentOutcome.SUCCESS, result, resultJson, latency, null);
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
                // request.requestPayload() 通常是 Jackson 反序列化出来的 Map/List —
                // 需要重新映射到工具方法声明的具体 DTO 类型，否则反射调用会抛
                // "argument type mismatch"。策略：JSON round-trip 一次。
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