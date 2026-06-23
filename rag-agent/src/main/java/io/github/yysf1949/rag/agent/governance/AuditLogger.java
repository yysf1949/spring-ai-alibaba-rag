package io.github.yysf1949.rag.agent.governance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * 审计日志器 — 结构化日志 + Micrometer 指标双写。
 *
 * <h2>对齐「路条编程」文章 §"没有可观测性，就不可能放心让 Agent 处理真实业务"</h2>
 * <p>每次工具调用产生结构化 JSON 日志（方便 ELK/Grafana Loki 采集），
 * 同时更新 Micrometer counter（agent.audit.total by tool/outcome）。</p>
 *
 * <h2>记录路径</h2>
 * <ol>
 *   <li>SLF4J 结构化日志 — JSON 格式，可被 Filebeat/Fluentd 采集</li>
 *   <li>Micrometer counter — agent.audit.total，按 tool + outcome 切分</li>
 * </ol>
 */
@Component
public class AuditLogger {

    private static final Logger log = LoggerFactory.getLogger(AuditLogger.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final MeterRegistry registry;
    private final SensitiveDataMasker masker;

    @org.springframework.beans.factory.annotation.Autowired
    public AuditLogger(MeterRegistry registry) {
        this(registry, new SensitiveDataMasker());
    }

    public AuditLogger(MeterRegistry registry, SensitiveDataMasker masker) {
        this.registry = registry;
        this.masker = masker == null ? new SensitiveDataMasker() : masker;
    }

    /**
     * 记录审计事件 — 结构化日志 + Micrometer counter。
     */
    public void log(AuditEvent event) {
        if (event == null) return;
        try {
            // 1. 结构化 JSON 日志
            String json = toJson(event);
            log.info(json);

            // 2. Micrometer counter
            Counter.builder("agent.audit.total")
                    .description("Total audit events")
                    .tag("tool", event.toolName() == null ? "unknown" : event.toolName())
                    .tag("outcome", event.outcome() == null ? "unknown" : event.outcome())
                    .register(registry)
                    .increment();
        } catch (Exception e) {
            log.warn("AuditLogger failed to log event: {}", e.getMessage());
        }
    }

    /**
     * 便捷方法 — 从工具调用参数构造并记录审计事件。
     */
    public void logToolInvocation(String toolName, String riskLevel,
                                  String input, String outcome,
                                  long latencyMs, String error) {
        String maskedInput = masker.mask(input);
        String traceId = TraceContext.current();
        AuditEvent event = AuditEvent.of(
                traceId,
                null, // tenantId — 由调用方在有上下文时补充
                null, // userId
                null, // sessionId
                toolName,
                riskLevel,
                maskedInput,
                null, // outputSummary
                outcome,
                latencyMs,
                error
        );
        log(event);
    }

    private static String toJson(AuditEvent event) {
        try {
            ObjectNode node = MAPPER.createObjectNode();
            node.put("auditId", event.auditId());
            node.put("traceId", event.traceId());
            node.put("tenantId", event.tenantId());
            node.put("userId", event.userId());
            node.put("sessionId", event.sessionId());
            node.put("toolName", event.toolName());
            node.put("riskLevel", event.riskLevel());
            node.put("inputParams", event.inputParams());
            node.put("outputSummary", event.outputSummary());
            node.put("outcome", event.outcome());
            node.put("latencyMs", event.latencyMs());
            node.put("errorMessage", event.errorMessage());
            node.put("timestamp", event.timestamp() == null ? null : event.timestamp().toString());
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            return "{\"error\":\"failed to serialize audit event\",\"message\":\"" + e.getMessage() + "\"}";
        }
    }
}
