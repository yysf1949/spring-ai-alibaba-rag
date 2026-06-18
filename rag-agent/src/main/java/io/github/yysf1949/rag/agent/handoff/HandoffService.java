package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

/**
 * 转人工服务 — 编排层在以下情况调用 {@link #handoff(HandoffContext)}：
 * <ul>
 *   <li>RiskGate 抛 {@link io.github.yysf1949.rag.agent.exception.AmountLimitExceededException}</li>
 *   <li>RiskGate 抛 L4 admin role 拒绝</li>
 *   <li>编排层检测到需要人工的特殊分支</li>
 * </ul>
 */
@Service
public class HandoffService {

    private final HumanReviewQueue queue;
    private final AgentMetrics metrics;

    public HandoffService(HumanReviewQueue queue, AgentMetrics metrics) {
        this.queue = queue;
        this.metrics = metrics;
    }

    public HumanReviewQueue.QueueItem handoff(HandoffContext ctx) {
        var item = new HumanReviewQueue.QueueItem(
                newHandoffId(),
                ctx,
                ctx.identity().tenantId(),
                null, null, null,
                Instant.now().toString(),
                null);
        queue.enqueue(item);
        metrics.recordHandoff(ctx.toolName(), ctx.reason().name(), ctx.channel().name());
        return item;
    }

    public HumanReviewQueue.QueueItem complete(String handoffId, String resolution,
                                               String resolvedBy, String note) {
        return queue.complete(handoffId, resolution, resolvedBy, note)
                .orElseThrow(() -> new IllegalArgumentException("Handoff not found: " + handoffId));
    }

    private static String newHandoffId() {
        return "HO-" + UUID.randomUUID().toString().substring(0, 8);
    }
}