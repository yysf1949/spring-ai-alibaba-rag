package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class HumanReviewQueueTest {

    @Test
    void listPendingFiltersByTenantAndResolution() {
        var queue = new HumanReviewQueue();
        AgentIdentity id1 = new AgentIdentity("tenant-1", "u1", "s1", Set.of());
        AgentIdentity id2 = new AgentIdentity("tenant-2", "u2", "s2", Set.of());
        var ctx1 = HandoffContext.forAmountLimit(id1, "create_refund", 1000L, 500L, List.of());
        var ctx2 = HandoffContext.forAmountLimit(id2, "create_refund", 1000L, 500L, List.of());

        var i1 = queue.enqueue(new HumanReviewQueue.QueueItem(
                "HO-1", ctx1, "tenant-1", null, null, null, "2026-06-18T10:00:00Z", null));
        queue.enqueue(new HumanReviewQueue.QueueItem(
                "HO-2", ctx2, "tenant-2", null, null, null, "2026-06-18T10:00:00Z", null));

        var pending1 = queue.listPending("tenant-1");
        var pending2 = queue.listPending("tenant-2");
        assertThat(pending1).hasSize(1);
        assertThat(pending1.get(0).handoffId()).isEqualTo("HO-1");
        assertThat(pending2).hasSize(1);
        assertThat(pending2.get(0).handoffId()).isEqualTo("HO-2");
    }

    @Test
    void completeRemovesItem() {
        var queue = new HumanReviewQueue();
        var ctx = HandoffContext.forAmountLimit(
                new AgentIdentity("t1", "u1", "s1", Set.of()),
                "create_refund", 1000L, 500L, List.of());
        queue.enqueue(new HumanReviewQueue.QueueItem(
                "HO-1", ctx, "t1", null, null, null, "now", null));

        var completed = queue.complete("HO-1", "APPROVED", "admin", "ok");
        assertThat(completed).isPresent();
        assertThat(completed.get().resolution()).isEqualTo("APPROVED");

        assertThat(queue.listPending("t1")).isEmpty();
    }
}