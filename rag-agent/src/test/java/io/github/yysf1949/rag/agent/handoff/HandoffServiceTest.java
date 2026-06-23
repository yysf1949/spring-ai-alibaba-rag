package io.github.yysf1949.rag.agent.handoff;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AgentMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class HandoffServiceTest {

    private HumanReviewQueue queue;
    private HandoffService service;

    @BeforeEach
    void setUp() {
        queue = new HumanReviewQueue();
        AgentMetrics metrics = mock(AgentMetrics.class);
        service = new HandoffService(queue, metrics);
    }

    @Test
    void handoffForAmountLimitEnqueues() {
        AgentIdentity id = new AgentIdentity("tenant-1", "user-1", "session-1", Set.of("user"));
        var ctx = HandoffContext.forAmountLimit(id, "create_refund",
                1000_00L, 500_00L, List.of("kb_search", "get_order"));
        service.handoff(ctx);

        var pending = queue.listPending("tenant-1");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).context().toolName()).isEqualTo("create_refund");
        assertThat(pending.get(0).context().reason()).isEqualTo(HandoffReason.AMOUNT_LIMIT_EXCEEDED);
    }

    @Test
    void handoffForInsufficientPrivilegeEnqueues() {
        AgentIdentity id = new AgentIdentity("tenant-1", "user-1", "session-1", Set.of("user"));
        var ctx = HandoffContext.forInsufficientPrivilege(id, "direct_refund",
                List.of("kb_search"));
        service.handoff(ctx);

        var pending = queue.listPending("tenant-1");
        assertThat(pending).hasSize(1);
        assertThat(pending.get(0).context().reason()).isEqualTo(HandoffReason.INSUFFICIENT_PRIVILEGE);
        assertThat(pending.get(0).context().channel()).isEqualTo(HandoffChannel.LIVE_CHAT);
    }

    @Test
    void completeHandoffRemovesFromQueue() {
        AgentIdentity id = new AgentIdentity("tenant-1", "user-1", "session-1", Set.of("user"));
        var ctx = HandoffContext.forAmountLimit(id, "create_refund",
                1000_00L, 500_00L, List.of());
        service.handoff(ctx);
        var pendingBefore = queue.listPending("tenant-1");
        assertThat(pendingBefore).hasSize(1);

        service.complete(pendingBefore.get(0).handoffId(), "APPROVED",
                "admin-1", "手动审批通过");

        var pendingAfter = queue.listPending("tenant-1");
        assertThat(pendingAfter).isEmpty();
    }
}
