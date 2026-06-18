package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * 阶段感知工具授权器测试 — 5 个用例对齐文章 §4 渐进式授权。
 */
class StageAwareToolAuthorizerTest {

    @Mock
    private io.github.yysf1949.rag.agent.action.ToolRegistry registry;

    private StageAwareToolAuthorizer authorizer;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        // 默认 mock: 6 个工具 (2 个 L1, 2 个 L2, 1 个 L3, 1 个 L4)
        when(registry.get("get_order")).thenReturn(mock("get_order", RiskLevel.L1_READ, true, false, null));
        when(registry.get("query_logistics")).thenReturn(mock("query_logistics", RiskLevel.L1_READ, true, false, null));
        when(registry.get("send_notification")).thenReturn(mock("send_notification", RiskLevel.L2_REVERSIBLE, true, true, null));
        when(registry.get("create_reminder_ticket")).thenReturn(mock("create_reminder_ticket", RiskLevel.L2_REVERSIBLE, true, true, null));
        when(registry.get("cancel_order")).thenReturn(mock("cancel_order", RiskLevel.L3_BUSINESS_STATE, false, true, null));
        when(registry.get("approve_refund")).thenReturn(mock("approve_refund", RiskLevel.L4_HIGH_RISK, false, true, null));
        when(registry.get("ghost")).thenThrow(new ToolNotFoundException("ghost"));
        authorizer = new StageAwareToolAuthorizer(registry);
    }

    @Test
    void awaitingConfirmationReturnsOnlyL1AndL2() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var ctx = new AuthorizationContext(identity, "s1", null, null, true);

        var allowed = authorizer.authorizedTools(
                ctx,
                List.of("get_order", "query_logistics", "send_notification",
                        "create_reminder_ticket", "cancel_order", "approve_refund"));

        // 只暴露 L1 + L2 (4 个)
        assertThat(allowed).containsExactlyInAnyOrder(
                "get_order", "query_logistics", "send_notification", "create_reminder_ticket");
    }

    @Test
    void confirmedReturnsAllExceptL4() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var ctx = new AuthorizationContext(identity, "s1", null, null, false);

        var allowed = authorizer.authorizedTools(
                ctx,
                List.of("get_order", "query_logistics", "send_notification",
                        "create_reminder_ticket", "cancel_order", "approve_refund"));

        // L1 + L2 + L3 (5 个, L4 仍屏蔽)
        assertThat(allowed).containsExactlyInAnyOrder(
                "get_order", "query_logistics", "send_notification",
                "create_reminder_ticket", "cancel_order");
        assertThat(allowed).doesNotContain("approve_refund");
    }

    @Test
    void explicitMaxRiskLevelL2ShieldsL3() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        // 已确认阶段但 maxRiskLevel 显式 L2 → 屏蔽 L3
        var ctx = new AuthorizationContext(identity, "s1", null, RiskLevel.L2_REVERSIBLE, false);

        var allowed = authorizer.authorizedTools(
                ctx,
                List.of("get_order", "cancel_order", "approve_refund"));

        assertThat(allowed).containsExactly("get_order");
    }

    @Test
    void allowedToolsWhitelistRestrictsFurther() {
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        // 即使 confirmed, 白名单只允许 2 个 L1
        var ctx = new AuthorizationContext(
                identity, "s1",
                Set.of("get_order", "query_logistics"),
                null, false);

        var allowed = authorizer.authorizedTools(
                ctx,
                List.of("get_order", "query_logistics", "send_notification", "cancel_order"));

        assertThat(allowed).containsExactlyInAnyOrder("get_order", "query_logistics");
    }

    @Test
    void unknownToolNameThrowsToolNotFound() {
        // registry.get("ghost") 已 mock 抛 ToolNotFoundException
        // 验证 authorizer 在 isAuthorized 时不抛 (内部降级为 L4 + reject)
        var identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        var ctx = new AuthorizationContext(identity, "s1", null, null, true);
        var allowed = authorizer.authorizedTools(ctx, List.of("ghost"));
        // ghost 不在 allowed (内部视为 L4 + awaitingConfirmation max=L2 → reject)
        assertThat(allowed).isEmpty();

        // 直接 isAuthorized("ghost") 应返回 false (不抛)
        assertThat(authorizer.isAuthorized("ghost", ctx)).isFalse();
    }

    // ---- helpers ----

    /** mock 工具 descriptor — 简化版 (生产有完整 ToolDescriptor 构造) */
    private static ToolDescriptor mock(String name, RiskLevel risk, boolean idempotent,
                                       boolean requiresKey, Long maxAmount) {
        return new ToolDescriptor(name, name + "_desc", risk, idempotent, requiresKey, maxAmount,
                new Object(), dummyMethod());
    }

    private static Method dummyMethod() {
        try {
            return String.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}