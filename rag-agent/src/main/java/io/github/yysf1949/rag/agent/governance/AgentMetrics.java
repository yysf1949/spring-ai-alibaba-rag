package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Agent 评估指标 — 对齐「路条编程」文章 §"评估指标要变"。
 *
 * <h2>核心指标</h2>
 * <ol>
 *   <li><b>agent.tool.invocations</b> — 端到端调用计数（带 outcome 标签）</li>
 *   <li><b>agent.tool.latency</b> — 端到端耗时分布（Timer，按 tool 切分）</li>
 *   <li><b>agent.handoffs</b> — 转人工次数（按 reason/channel 切分）</li>
 *   <li><b>agent.idempotency.replays</b> — 幂等回放次数（评估"系统稳定性"）</li>
 *   <li><b>agent.tool.errors</b> — 错误执行次数（治理层/编排层异常，跟普通 FAILURE 区分）</li>
 * </ol>
 *
 * <h2>业务指标增强</h2>
 * <ol>
 *   <li><b>agent.tool.business_errors</b> — 业务执行错误（区别于治理层错误）</li>
 *   <li><b>agent.tool.confirmations</b> — 用户确认率</li>
 *   <li><b>agent.tool.rollbacks</b> — 回滚次数</li>
 *   <li><b>agent.tool.success_rate</b> — 按 tool 的成功率 Gauge</li>
 *   <li><b>agent.conversation.resolution_rate</b> — 端到端问题解决率</li>
 *   <li><b>agent.conversation.handoff_quality</b> — 转人工质量</li>
 * </ol>
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    /** 成功/失败原子计数，用于 success_rate Gauge */
    private final ConcurrentHashMap<String, AtomicLong> successCounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> failureCounts = new ConcurrentHashMap<>();

    @Autowired
    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 带 SatisfactionSurveyPort 的构造器 — 用于注册 resolution_rate Gauge。
     */
    public AgentMetrics(MeterRegistry registry, SatisfactionSurveyPort surveyPort) {
        this.registry = registry;
        if (surveyPort != null) {
            Gauge.builder("agent.conversation.resolution_rate", () -> {
                        var all = surveyPort.countAll();
                        return all == 0 ? 0.0 : (double) surveyPort.countResolved() / all;
                    })
                    .description("端到端问题解决率")
                    .register(registry);
        }
    }

    /** 工具调用埋点（每次 invoke 调一次） */
    public void recordToolInvocation(String toolName, AgentOutcome outcome, long latencyMs) {
        String traceId = TraceContext.current();
        var counter = (traceId == null)
                ? registry.counter("agent.tool.invocations",
                        "tool", toolName, "outcome", outcome.name())
                : registry.counter("agent.tool.invocations",
                        "tool", toolName, "outcome", outcome.name(), "traceId", traceId);
        counter.increment();

        // 更新 success_rate 计数
        if (outcome == AgentOutcome.SUCCESS || outcome == AgentOutcome.REPLAY) {
            successCounts.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
        } else if (outcome == AgentOutcome.FAILURE) {
            failureCounts.computeIfAbsent(toolName, k -> new AtomicLong()).incrementAndGet();
        }

        Timer.Builder builder = Timer.builder("agent.tool.latency")
                .description("Agent tool invocation latency")
                .tag("tool", toolName);
        if (traceId != null) {
            builder.tag("traceId", traceId);
        }
        Timer timer = builder.register(registry);
        timer.record(Duration.ofMillis(latencyMs));
    }

    /** 转人工埋点（HANDOFF_REQUIRED 时调） */
    public void recordHandoff(String toolName, String reason, String handoffChannel) {
        registry.counter("agent.handoffs",
                "tool", toolName,
                "reason", reason,
                "channel", handoffChannel).increment();
    }

    /** 幂等回放埋点（REPLAY outcome 时调） */
    public void recordIdempotencyReplay(String toolName) {
        registry.counter("agent.idempotency.replays",
                "tool", toolName).increment();
    }

    /** 错误执行埋点（治理层/编排层异常，不是工具业务 FAILURE） */
    public void recordErrorExecution(String toolName, String errorType) {
        registry.counter("agent.tool.errors",
                "tool", toolName,
                "type", errorType).increment();
    }

    // ========== 业务指标增强 ==========

    /**
     * 业务执行错误（区别于治理层错误）。
     * errorType 示例: AMOUNT_EXCEEDED / ORDER_NOT_FOUND / IDEMPOTENCY_CONFLICT 等。
     */
    public void recordBusinessError(String tool, String errorType) {
        registry.counter("agent.tool.business_errors",
                "tool", tool,
                "errorType", errorType).increment();
    }

    /**
     * 用户确认率 — confirmed=true 表示用户确认执行，false 表示拒绝。
     */
    public void recordConfirmation(String tool, boolean confirmed) {
        registry.counter("agent.tool.confirmations",
                "tool", tool,
                "confirmed", String.valueOf(confirmed)).increment();
    }

    /**
     * 回滚次数 — 记录工具执行后被回滚的情况。
     */
    public void recordRollback(String tool, String reason) {
        registry.counter("agent.tool.rollbacks",
                "tool", tool,
                "reason", reason).increment();
    }

    /**
     * 转人工质量 — 有无上下文信息传递给人工客服。
     */
    public void recordHandoffQuality(boolean hasContext, String reason) {
        registry.counter("agent.conversation.handoff_quality",
                "hasContext", String.valueOf(hasContext),
                "handoffReason", reason).increment();
    }
}
