package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.api.AgentOutcome;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    private MeterRegistry registry;
    private AgentMetrics metrics;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        metrics = new AgentMetrics(registry);
    }

    @Test
    void recordsToolInvocationCounter() {
        metrics.recordToolInvocation("kb_search", AgentOutcome.SUCCESS, 42L);
        double count = registry.counter("agent.tool.invocations",
                "tool", "kb_search", "outcome", "SUCCESS").count();
        assertThat(count).isEqualTo(1.0);
    }

    @Test
    void recordsToolLatencyTimer() {
        metrics.recordToolInvocation("kb_search", AgentOutcome.SUCCESS, 100L);
        metrics.recordToolInvocation("kb_search", AgentOutcome.SUCCESS, 200L);
        long count = registry.timer("agent.tool.latency",
                "tool", "kb_search").count();
        assertThat(count).isEqualTo(2L);
        assertThat(registry.timer("agent.tool.latency",
                "tool", "kb_search").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(300.0);
    }

    @Test
    void recordsHandoffCounter() {
        metrics.recordHandoff("create_refund", "amount_exceeded", "WORK_ORDER");
        assertThat(registry.counter("agent.handoffs",
                "tool", "create_refund", "reason", "amount_exceeded",
                "channel", "WORK_ORDER").count()).isEqualTo(1.0);
    }

    @Test
    void recordsIdempotencyReplays() {
        metrics.recordIdempotencyReplay("create_reminder_ticket");
        metrics.recordIdempotencyReplay("create_reminder_ticket");
        assertThat(registry.counter("agent.idempotency.replays",
                "tool", "create_reminder_ticket").count()).isEqualTo(2.0);
    }

    @Test
    void recordsErrorExecutionCounter() {
        // "错误执行率" — 跟普通 FAILURE 区分，标记为 agent 自身错误（治理层/编排层异常）
        metrics.recordErrorExecution("kb_search", "NPE");
        assertThat(registry.counter("agent.tool.errors",
                "tool", "kb_search", "type", "NPE").count()).isEqualTo(1.0);
    }
}
