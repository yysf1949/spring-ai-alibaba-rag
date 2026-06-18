package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.StageAwareToolAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Spring AI 适配器动态授权测试 — 验证 Phase 14 P1 集成。
 *
 * <p>根据 ctx.requiresConfirmation 决定 callback 数组大小:
 * <ul>
 *   <li>awaitingConfirmation → 5 (L1 + L2 工具, 屏蔽 L3 cancel_order)</li>
 *   <li>confirmed → 6 (含 L3, 但仍屏蔽 L4 approve_refund)</li>
 *   <li>permissive → 6 (跟 confirmed 一样, L4 仍屏蔽)</li>
 * </ul></p>
 */
class SpringAiAgentAdapterDynamicAuthTest {

    private InMemoryToolRegistry registry;
    private SpringAiAgentAdapter adapter;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        registry = new InMemoryToolRegistry();
        // 反射注入 6 个工具 (3 L1 + 2 L2 + 1 L3)
        Field f = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        f.setAccessible(true);
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) f.get(registry);
        map.put("get_order", mock("get_order", RiskLevel.L1_READ));
        map.put("calculate_refund_amount", mock("calculate_refund_amount", RiskLevel.L1_READ));
        map.put("get_member_benefits", mock("get_member_benefits", RiskLevel.L1_READ));
        map.put("send_notification", mock("send_notification", RiskLevel.L2_REVERSIBLE));
        map.put("create_reminder_ticket", mock("create_reminder_ticket", RiskLevel.L2_REVERSIBLE));
        map.put("cancel_order", mock("cancel_order", RiskLevel.L3_BUSINESS_STATE));

        var authorizer = new StageAwareToolAuthorizer(registry);
        adapter = new SpringAiAgentAdapter(registry, authorizer);
    }

    @Test
    void awaitingConfirmationFiltersOutL3() {
        var identity = new io.github.yysf1949.rag.agent.governance.AgentIdentity(
                "t1", "u1", "s1", Set.of("user"));
        var ctx = AuthorizationContext.awaitingConfirmation(identity);

        var callbacks = adapter.getFunctionCallbacks(ctx);

        // L1 + L2 = 5 个 (屏蔽 L3 cancel_order)
        assertThat(callbacks).hasSize(5);
        var names = Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .toList();
        assertThat(names).doesNotContain("cancel_order");
        assertThat(names).containsExactlyInAnyOrder(
                "get_order", "calculate_refund_amount", "get_member_benefits",
                "send_notification", "create_reminder_ticket");
    }

    @Test
    void confirmedIncludesL3() {
        var identity = new io.github.yysf1949.rag.agent.governance.AgentIdentity(
                "t1", "u1", "s1", Set.of("user"));
        var ctx = AuthorizationContext.confirmed(identity);

        var callbacks = adapter.getFunctionCallbacks(ctx);

        // L1 + L2 + L3 = 6 个
        assertThat(callbacks).hasSize(6);
        var names = Arrays.stream(callbacks)
                .map(cb -> cb.getToolDefinition().name())
                .toList();
        assertThat(names).contains("cancel_order");
    }

    private static ToolDescriptor mock(String name, RiskLevel risk) {
        try {
            // 1-arg 业务 DTO 方法 (FunctionToolCallback builder 要求 inputType 存在)
            Method m = FakeDtoBean.class.getMethod("run", FakeDtoBean.In.class);
            return new ToolDescriptor(name, name + "_desc", risk, true, false, null,
                    new FakeDtoBean(), m);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    /** 测试用 1-arg DTO bean, 跟 SpringAiAgentAdapterTest 同款 (避免 mock 自定义 Method). */
    public static class FakeDtoBean {
        public record In(String q) { }
        public record Out(String result) { }
        public Out run(In in) { return new Out("ok:" + in.q()); }
    }
}