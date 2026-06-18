package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.StageAwareToolAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 15 Task 2: ChatClientService mock 测试 — 验证 AuthorizationContext → LLM 看到 tool 数目的过滤逻辑.
 *
 * <h2>mock 策略</h2>
 * <p>不真发 LLM 请求, 而是把 ChatClient.prompt() 链 mock 成返回特定 content.
 * 验证点: ChatClient.prompt() 链被调用时, toolCallbacks 参数数量符合 ctx 过滤后的预期.</p>
 *
 * <h2>测试数据</h2>
 * <p>ToolRegistry 注入 7 个 tool (L1×3, L2×2, L3×2):</p>
 * <ul>
 *   <li>L1: {@code get_order}, {@code calculate_refund_amount}, {@code get_member_benefits}</li>
 *   <li>L2: {@code send_notification}, {@code create_reminder_ticket}</li>
 *   <li>L3: {@code cancel_order}, {@code create_refund}</li>
 * </ul>
 *
 * <h2>期望过滤</h2>
 * <ul>
 *   <li>confirmed ctx (L1-L3) → 7 个 tool</li>
 *   <li>awaitingConfirmation ctx (L1+L2) → 5 个 tool</li>
 *   <li>null ctx → fallback permissive (L1-L3) → 7 个 tool</li>
 * </ul>
 *
 * <h2>沿用 Phase 14 ship pattern</h2>
 * <p>反射注入 {@code descriptors} 字段 + {@link FakeDtoBean} 1-arg 方法, 跟
 * {@code SpringAiAgentAdapterDynamicAuthTest} 同款 (避免 mock 自定义 Method).</p>
 */
@DisplayName("ChatClientService Mock Test")
class ChatClientServiceMockTest {

    private InMemoryToolRegistry registry;
    private StageAwareToolAuthorizer authorizer;
    private SpringAiAgentAdapter adapter;
    private ChatClient mockChatClient;
    private ChatClientService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        // 1. 7 tool 的 fake descriptors, 反射注入 (跟 Phase 14 同款 pattern)
        registry = new InMemoryToolRegistry();
        Field f = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        f.setAccessible(true);
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) f.get(registry);
        // L1 × 3
        map.put("get_order", mockTool("get_order", RiskLevel.L1_READ));
        map.put("calculate_refund_amount", mockTool("calculate_refund_amount", RiskLevel.L1_READ));
        map.put("get_member_benefits", mockTool("get_member_benefits", RiskLevel.L1_READ));
        // L2 × 2
        map.put("send_notification", mockTool("send_notification", RiskLevel.L2_REVERSIBLE));
        map.put("create_reminder_ticket", mockTool("create_reminder_ticket", RiskLevel.L2_REVERSIBLE));
        // L3 × 2
        map.put("cancel_order", mockTool("cancel_order", RiskLevel.L3_BUSINESS_STATE));
        map.put("create_refund", mockTool("create_refund", RiskLevel.L3_BUSINESS_STATE));

        // 2. Authorizer + Adapter + Service
        authorizer = new StageAwareToolAuthorizer(registry);
        adapter = new SpringAiAgentAdapter(registry, authorizer);

        // 3. Mock ChatClient — 链式 prompt() 调用直接返回 content
        mockChatClient = mock(ChatClient.class);
        service = new ChatClientService(mockChatClient, adapter, authorizer);
    }

    @Test
    @DisplayName("confirmed ctx → LLM 看到 L1+L2+L3 全部 7 个 tool")
    void chat_withConfirmedCtx_callsLLMWithFilteredCallbacks() {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenAnswer(inv -> {
            List<ToolCallback> arg = inv.getArgument(0);
            // 验证 1: ctx filter 后数量 = 7 (L1×3 + L2×2 + L3×2)
            assertThat(arg).hasSize(7);
            List<String> names = arg.stream()
                    .map(cb -> ((FunctionToolCallback<?, ?>) cb).getToolDefinition().name())
                    .toList();
            assertThat(names).containsExactlyInAnyOrder(
                    "get_order", "calculate_refund_amount", "get_member_benefits",
                    "send_notification", "create_reminder_ticket",
                    "cancel_order", "create_refund");
            return reqSpec;
        });
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("mock-confirmed-response");

        AgentIdentity identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        String result = service.chat("hi", AuthorizationContext.confirmed(identity));

        assertThat(result).isEqualTo("mock-confirmed-response");
    }

    @Test
    @DisplayName("awaitingConfirmation ctx → LLM 看到 L1+L2 共 5 个 tool (L3 全部过滤掉)")
    void chat_withAwaitingCtx_filtersOutL3Tools() {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenAnswer(inv -> {
            List<ToolCallback> arg = inv.getArgument(0);
            // 验证 2: ctx filter 后数量 = 5 (L1×3 + L2×2)
            assertThat(arg).hasSize(5);
            List<String> names = arg.stream()
                    .map(cb -> ((FunctionToolCallback<?, ?>) cb).getToolDefinition().name())
                    .toList();
            assertThat(names).doesNotContain("cancel_order", "create_refund");
            assertThat(names).containsExactlyInAnyOrder(
                    "get_order", "calculate_refund_amount", "get_member_benefits",
                    "send_notification", "create_reminder_ticket");
            return reqSpec;
        });
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("mock-awaiting-response");

        AgentIdentity identity = new AgentIdentity("t1", "u1", "s1", Set.of("user"));
        String result = service.chat("hi", AuthorizationContext.awaitingConfirmation(identity));

        assertThat(result).isEqualTo("mock-awaiting-response");
    }

    @Test
    @DisplayName("null ctx → fallback permissive → LLM 看到 7 个 tool (L1-L3)")
    void chat_withNullCtx_usesPermissiveFallback() {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenAnswer(inv -> {
            List<ToolCallback> arg = inv.getArgument(0);
            // 验证 3: permissive ctx = 7 个 (同 confirmed, 但 identity=null 退化路径)
            assertThat(arg).hasSize(7);
            return reqSpec;
        });
        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("mock-null-fallback-response");

        String result = service.chat("hi", null);

        assertThat(result).isEqualTo("mock-null-fallback-response");
    }

    /** 跟 SpringAiAgentAdapterDynamicAuthTest 同款 FakeDtoBean. */
    private static ToolDescriptor mockTool(String name, RiskLevel risk) {
        try {
            Method m = FakeDtoBean.class.getMethod("run", FakeDtoBean.In.class);
            return new ToolDescriptor(name, name + "_desc", risk, true, false, null, false,
                    new FakeDtoBean(), m);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }

    public static class FakeDtoBean {
        public record In(String q) { }
        public record Out(String result) { }
        public Out run(In in) { return new Out("ok:" + in.q()); }
    }
}