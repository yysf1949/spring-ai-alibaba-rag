package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 4 大评估指标 — 对齐「路条编程」文章 §"评估指标要变"。
 *
 * <h2>4 个核心指标</h2>
 * <ol>
 *   <li><b>agent.tool.invocations</b> — 端到端调用计数（带 outcome 标签）</li>
 *   <li><b>agent.tool.latency</b> — 端到端耗时分布（Timer，按 tool 切分）</li>
 *   <li><b>agent.handoffs</b> — 转人工次数（按 reason/channel 切分）</li>
 *   <li><b>agent.idempotency.replays</b> — 幂等回放次数（评估"系统稳定性"）</li>
 *   <li><b>agent.tool.errors</b> — 错误执行次数（治理层/编排层异常，跟普通 FAILURE 区分）</li>
 * </ol>
 *
 * <h2>不是端到端解决率 — 那要靠业务反馈</h2>
 * <p>"端到端问题解决率"（文章要求）需要业务系统反馈用户最终是否解决了问题，
 * 这是 out-of-band 信号，不在 Agent Metrics 范围内。本类只覆盖"Agent 自身可观测"。</p>
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 工具调用埋点（每次 invoke 调一次） */
    public void recordToolInvocation(String toolName, AgentOutcome outcome, long latencyMs) {
        registry.counter("agent.tool.invocations",
                "tool", toolName,
                "outcome", outcome.name()).increment();

        Timer timer = Timer.builder("agent.tool.latency")
                .description("Agent tool invocation latency")
                .tag("tool", toolName)
                .register(registry);
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
}
