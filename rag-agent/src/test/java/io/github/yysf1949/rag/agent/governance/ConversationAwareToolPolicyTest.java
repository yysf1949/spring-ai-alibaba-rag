package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 会话感知工具选择策略测试。
 */
class ConversationAwareToolPolicyTest {

    @Mock
    private SatisfactionSurveyPort satisfactionSurvey;

    @Mock
    private ToolRegistry registry;

    private ConversationAwareToolPolicy policy;

    private static final List<String> ALL_TOOLS = List.of(
            "get_order", "query_logistics",           // L1
            "create_reminder_ticket", "send_notification",  // L2
            "create_complaint",                         // L2 (投诉)
            "cancel_order", "apply_price_protection"    // L3
    );

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        when(registry.get("get_order")).thenReturn(tool("get_order", RiskLevel.L1_READ));
        when(registry.get("query_logistics")).thenReturn(tool("query_logistics", RiskLevel.L1_READ));
        when(registry.get("create_reminder_ticket")).thenReturn(tool("create_reminder_ticket", RiskLevel.L2_REVERSIBLE));
        when(registry.get("send_notification")).thenReturn(tool("send_notification", RiskLevel.L2_REVERSIBLE));
        when(registry.get("create_complaint")).thenReturn(tool("create_complaint", RiskLevel.L2_REVERSIBLE));
        when(registry.get("cancel_order")).thenReturn(tool("cancel_order", RiskLevel.L3_BUSINESS_STATE));
        when(registry.get("apply_price_protection")).thenReturn(tool("apply_price_protection", RiskLevel.L3_BUSINESS_STATE, true));

        // 默认: 没有历史满意度记录
        when(satisfactionSurvey.findByConversation("c1")).thenReturn(List.of());

        policy = new ConversationAwareToolPolicy(satisfactionSurvey, registry);
    }

    @Test
    void earlyConversationOnlyL1() {
        // 前 3 轮 (messageCount ≤ 6) → 只有 L1
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 2, null, null);

        var result = policy.filterTools(ctx, ALL_TOOLS);

        assertThat(result).containsExactlyInAnyOrder("get_order", "query_logistics");
    }

    @Test
    void midConversationL1AndL2() {
        // 4-10 轮 (messageCount 7-20) → L1 + L2
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 10, null, null);

        var result = policy.filterTools(ctx, ALL_TOOLS);

        assertThat(result).containsExactlyInAnyOrder(
                "get_order", "query_logistics",
                "create_reminder_ticket", "send_notification", "create_complaint");
        assertThat(result).doesNotContain("cancel_order", "apply_price_protection");
    }

    @Test
    void lateConversationAllTools() {
        // >10 轮 (messageCount > 20) → 全部工具
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 22, null, null);

        var result = policy.filterTools(ctx, ALL_TOOLS);

        assertThat(result).containsExactlyInAnyOrder(
                "get_order", "query_logistics",
                "create_reminder_ticket", "send_notification", "create_complaint",
                "cancel_order", "apply_price_protection");
    }

    @Test
    void queryIntentOnlyL1() {
        // QUERY 意图, 即使在后期对话 → 只有 L1
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 22, null,
                ToolSelectionPolicy.UserIntent.QUERY);

        var result = policy.filterTools(ctx, ALL_TOOLS);

        assertThat(result).containsExactlyInAnyOrder("get_order", "query_logistics");
    }

    @Test
    void complaintIntentOpensComplaintTools() {
        // COMPLAINT 意图, 早期对话 → L1 + 投诉工具
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 2, null,
                ToolSelectionPolicy.UserIntent.COMPLAINT);

        var result = policy.filterTools(ctx, ALL_TOOLS);

        assertThat(result).containsExactlyInAnyOrder(
                "get_order", "query_logistics",
                "create_complaint", "create_reminder_ticket");
    }

    @Test
    void lowSatisfactionAutoUpgrade() {
        // 低满意度用户 → 自动升级一档
        when(satisfactionSurvey.findByConversation("c1"))
                .thenReturn(List.of(new SatisfactionSurveyPort.SurveyRecord(
                        "s1", "t1", "u1", "c1", 1, "bad", false, System.currentTimeMillis())));

        // 早期对话 (本来只有 L1), 低满意度 → 升级到 L2
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 2, null, null);

        var result = policy.filterTools(ctx, ALL_TOOLS);

        assertThat(result).containsExactlyInAnyOrder(
                "get_order", "query_logistics",
                "create_reminder_ticket", "send_notification", "create_complaint");
    }

    // ---- helpers ----

    private static ToolDescriptor tool(String name, RiskLevel risk) {
        return tool(name, risk, false);
    }

    private static ToolDescriptor tool(String name, RiskLevel risk, boolean requiresConfirmationToken) {
        return new ToolDescriptor(name, name + "_desc", risk, true, false, null,
                requiresConfirmationToken, new Object(), dummyMethod());
    }

    private static Method dummyMethod() {
        try {
            return String.class.getMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
