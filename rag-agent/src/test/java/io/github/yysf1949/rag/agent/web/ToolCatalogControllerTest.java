package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * ToolCatalogController 纯单元测试 — JUnit 5 + Mockito，不启动 Spring Context。
 *
 * <p>直接 {@code new} 控制器实例 + Mock {@link ToolRegistry}，
 * 验证 REST 层对 ToolRegistry → ToolSummary 的映射逻辑。</p>
 */
@DisplayName("ToolCatalogController — 纯单元测试")
class ToolCatalogControllerTest {

    private ToolRegistry toolRegistry;
    private ToolCatalogController controller;

    // ── 预设的 ToolDescriptor（bean/method 为 null，控制器不碰它们） ──

    private static final ToolDescriptor ORDER_QUERY = new ToolDescriptor(
            "order-query",
            "查询用户订单",
            RiskLevel.L1_READ,
            true, false, null, false,
            null, null);

    private static final ToolDescriptor CREATE_REFUND = new ToolDescriptor(
            "create-refund",
            "创建退款申请",
            RiskLevel.L3_BUSINESS_STATE,
            false, true, 50000L, true,
            null, null);

    private static final ToolDescriptor MANUAL_PRICE_CHANGE = new ToolDescriptor(
            "manual-price-change",
            "人工改价",
            RiskLevel.L4_HIGH_RISK,
            false, false, null, true,
            null, null);

    @BeforeEach
    void setUp() {
        toolRegistry = mock(ToolRegistry.class);
        controller = new ToolCatalogController(toolRegistry);
    }

    // ── 测试 GET /api/tools：返回所有工具 ──

    @Test
    @DisplayName("listTools() — 返回 ToolRegistry 中所有已注册工具")
    void listTools_returnsAllRegisteredTools() {
        // given
        when(toolRegistry.listNames()).thenReturn(List.of("order-query", "create-refund"));
        when(toolRegistry.get("order-query")).thenReturn(ORDER_QUERY);
        when(toolRegistry.get("create-refund")).thenReturn(CREATE_REFUND);

        // when
        List<ToolCatalogController.ToolSummary> result = controller.listTools();

        // then
        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("order-query");
        assertThat(result.get(0).description()).isEqualTo("查询用户订单");
        assertThat(result.get(1).name()).isEqualTo("create-refund");
        assertThat(result.get(1).description()).isEqualTo("创建退款申请");

        verify(toolRegistry).listNames();
        verify(toolRegistry).get("order-query");
        verify(toolRegistry).get("create-refund");
    }

    @Test
    @DisplayName("listTools() — ToolRegistry 为空时返回空列表")
    void listTools_emptyRegistry_returnsEmptyList() {
        // given
        when(toolRegistry.listNames()).thenReturn(List.of());

        // when
        List<ToolCatalogController.ToolSummary> result = controller.listTools();

        // then
        assertThat(result).isEmpty();
    }

    // ── 测试 GET /api/tools/{name}：返回指定工具 ──

    @Test
    @DisplayName("getTool(name) — 工具存在时返回对应 ToolSummary")
    void getTool_existingTool_returnsSummary() {
        // given
        when(toolRegistry.get("order-query")).thenReturn(ORDER_QUERY);

        // when
        ToolCatalogController.ToolSummary summary = controller.getTool("order-query");

        // then
        assertThat(summary.name()).isEqualTo("order-query");
        assertThat(summary.description()).isEqualTo("查询用户订单");
        assertThat(summary.riskLevel()).isEqualTo(RiskLevel.L1_READ);
        assertThat(summary.idempotent()).isTrue();
        assertThat(summary.requiresIdempotencyKey()).isFalse();
        assertThat(summary.maxAmountCents()).isNull();
        assertThat(summary.requiresConfirmationToken()).isFalse();

        verify(toolRegistry).get("order-query");
    }

    // ── 测试 GET /api/tools/{name}：工具不存在返回 404（抛 ToolNotFoundException） ──

    @Test
    @DisplayName("getTool(name) — 工具不存在时抛出 ToolNotFoundException（映射为 HTTP 404）")
    void getTool_nonExistingTool_throwsToolNotFoundException() {
        // given
        when(toolRegistry.get("non-existing")).thenThrow(new ToolNotFoundException("non-existing"));

        // when & then
        assertThatThrownBy(() -> controller.getTool("non-existing"))
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessageContaining("non-existing");

        verify(toolRegistry).get("non-existing");
    }

    // ── 测试工具列表包含风险等级信息 ──

    @Test
    @DisplayName("工具摘要包含完整的风险等级与治理字段")
    void listTools_riskLevelAndGovernanceFields_arePopulated() {
        // given — 3 种不同风险等级
        when(toolRegistry.listNames()).thenReturn(List.of("order-query", "create-refund", "manual-price-change"));
        when(toolRegistry.get("order-query")).thenReturn(ORDER_QUERY);
        when(toolRegistry.get("create-refund")).thenReturn(CREATE_REFUND);
        when(toolRegistry.get("manual-price-change")).thenReturn(MANUAL_PRICE_CHANGE);

        // when
        List<ToolCatalogController.ToolSummary> result = controller.listTools();

        // then — L1_READ
        ToolCatalogController.ToolSummary l1 = result.get(0);
        assertThat(l1.riskLevel()).isEqualTo(RiskLevel.L1_READ);
        assertThat(l1.idempotent()).isTrue();
        assertThat(l1.requiresIdempotencyKey()).isFalse();
        assertThat(l1.requiresConfirmationToken()).isFalse();

        // L3_BUSINESS_STATE
        ToolCatalogController.ToolSummary l3 = result.get(1);
        assertThat(l3.riskLevel()).isEqualTo(RiskLevel.L3_BUSINESS_STATE);
        assertThat(l3.requiresIdempotencyKey()).isTrue();
        assertThat(l3.maxAmountCents()).isEqualTo(50000L);
        assertThat(l3.requiresConfirmationToken()).isTrue();

        // L4_HIGH_RISK
        ToolCatalogController.ToolSummary l4 = result.get(2);
        assertThat(l4.riskLevel()).isEqualTo(RiskLevel.L4_HIGH_RISK);
        assertThat(l4.requiresConfirmationToken()).isTrue();
    }
}
