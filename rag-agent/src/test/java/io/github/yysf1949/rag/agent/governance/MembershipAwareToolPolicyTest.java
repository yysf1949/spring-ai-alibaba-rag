package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.builtin.port.MemberProfileRepositoryPort;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 会员等级感知工具选择策略测试。
 */
class MembershipAwareToolPolicyTest {

    @Mock
    private MemberProfileRepositoryPort memberProfileRepository;

    @Mock
    private ToolRegistry registry;

    private MembershipAwareToolPolicy policy;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);

        // mock 工具: 2 L1 + 1 L2 + 1 L3
        when(registry.get("get_order")).thenReturn(tool("get_order", RiskLevel.L1_READ));
        when(registry.get("query_logistics")).thenReturn(tool("query_logistics", RiskLevel.L1_READ));
        when(registry.get("create_reminder_ticket")).thenReturn(tool("create_reminder_ticket", RiskLevel.L2_REVERSIBLE));
        when(registry.get("apply_price_protection")).thenReturn(tool("apply_price_protection", RiskLevel.L3_BUSINESS_STATE, true));

        policy = new MembershipAwareToolPolicy(memberProfileRepository, registry);
    }

    @Test
    void goldMemberGetsAllTools() {
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", "GOLD", 4, null, null);
        var candidates = List.of("get_order", "query_logistics", "create_reminder_ticket", "apply_price_protection");

        var result = policy.filterTools(ctx, candidates);

        assertThat(result).containsExactlyInAnyOrder(
                "get_order", "query_logistics", "create_reminder_ticket", "apply_price_protection");
    }

    @Test
    void normalMemberExcludesL3Tools() {
        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", "NORMAL", 4, null, null);
        var candidates = List.of("get_order", "query_logistics", "create_reminder_ticket", "apply_price_protection");

        var result = policy.filterTools(ctx, candidates);

        // L3 apply_price_protection 被排除
        assertThat(result).containsExactlyInAnyOrder(
                "get_order", "query_logistics", "create_reminder_ticket");
        assertThat(result).doesNotContain("apply_price_protection");
    }

    @Test
    void unknownUserGetsOnlyL1() {
        // 未注册用户, repository 也查不到
        when(memberProfileRepository.findByTenantAndUser("t1", "u1"))
                .thenReturn(Optional.empty());

        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 4, null, null);
        var candidates = List.of("get_order", "query_logistics", "create_reminder_ticket", "apply_price_protection");

        var result = policy.filterTools(ctx, candidates);

        // 只有 L1 只读工具
        assertThat(result).containsExactlyInAnyOrder("get_order", "query_logistics");
    }

    @Test
    void unknownUserFallsBackToRepositoryLookup() {
        // tier 为 null, 但 repository 能查到 GOLD
        when(memberProfileRepository.findByTenantAndUser("t1", "u1"))
                .thenReturn(Optional.of(new MemberProfileRepositoryPort.MemberProfile(
                        "u1", "t1", "GOLD", 1000, List.of())));

        var ctx = new ToolSelectionPolicy.ToolSelectionContext(
                "t1", "u1", "c1", null, 4, null, null);
        var candidates = List.of("get_order", "query_logistics", "create_reminder_ticket", "apply_price_protection");

        var result = policy.filterTools(ctx, candidates);

        // repository 查到 GOLD → 全部通过
        assertThat(result).containsExactlyInAnyOrder(
                "get_order", "query_logistics", "create_reminder_ticket", "apply_price_protection");
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
