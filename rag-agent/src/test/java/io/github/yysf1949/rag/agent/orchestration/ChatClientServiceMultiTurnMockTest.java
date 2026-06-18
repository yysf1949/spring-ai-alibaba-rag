package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.api.ChatReply;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.StageAwareToolAuthorizer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import reactor.core.publisher.Flux;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 16 Task 4a: ChatClientService 多轮 + SSE 流式 mock 测试.
 *
 * <h2>覆盖点 (3 用例, 对应 Plan §2.7 #1-3)</h2>
 * <ul>
 *   <li>#1 chatWithMemory 透传 CONVERSATION_ID 到 advisor (消费者侧校验)</li>
 *   <li>#2 stream() 返回 Flux<String>, 每元素非空</li>
 *   <li>#3 stream 路径 ctx 过滤依然生效</li>
 * </ul>
 *
 * <h2>mock 沿用 Phase 15 ChatClientServiceMockTest 同款</h2>
 * <p>反射注入 descriptors + FakeDtoBean, 避免 mock 自定义 Method.</p>
 */
@DisplayName("ChatClientService Multi-Turn + Stream Mock Test")
class ChatClientServiceMultiTurnMockTest {

    private InMemoryToolRegistry registry;
    private StageAwareToolAuthorizer authorizer;
    private SpringAiAgentAdapter adapter;
    private ChatClient mockChatClient;
    private ChatClientService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        registry = new InMemoryToolRegistry();
        Field f = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        f.setAccessible(true);
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) f.get(registry);
        // L1 × 2, L2 × 1, L3 × 1 — 凑齐 3 个 risk 级, ctx 过滤差异才能体现
        map.put("get_order", mockTool("get_order", RiskLevel.L1_READ));
        map.put("kb_search", mockTool("kb_search", RiskLevel.L1_READ));
        map.put("create_reminder_ticket", mockTool("create_reminder_ticket", RiskLevel.L2_REVERSIBLE));
        map.put("cancel_order", mockTool("cancel_order", RiskLevel.L3_BUSINESS_STATE));

        authorizer = new StageAwareToolAuthorizer(registry);
        adapter = new SpringAiAgentAdapter(registry, authorizer);
        mockChatClient = mock(ChatClient.class);
        service = new ChatClientService(mockChatClient, adapter, authorizer);
    }

    /**
     * Plan #1: chatWithMemory_sameConvId_passesConvIdParam
     * <p>让 advisor Consumer 实际跑起来, 捕获 param(key, value) 调用, 验证 conversationId 透传.</p>
     */
    @Test
    @DisplayName("chatWithMemory 透传 conversationId 到 advisor")
    void chatWithMemory_sameConvId_passesConvIdParam() {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);
        AtomicReference<String> capturedKey = new AtomicReference<>();
        AtomicReference<Object> capturedValue = new AtomicReference<>();

        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenReturn(reqSpec);

        // advisors(Consumer) — 拿 mock AdvisorSpec, 让 consumer 实际调到 .param(key, value)
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        when(advisorSpec.param(anyString(), any())).thenAnswer(inv -> {
            capturedKey.set(inv.getArgument(0));
            capturedValue.set(inv.getArgument(1));
            return advisorSpec;
        });
        doAnswer(inv -> {
            java.util.function.Consumer<ChatClient.AdvisorSpec> consumer = inv.getArgument(0);
            consumer.accept(advisorSpec);
            return reqSpec;
        }).when(reqSpec).advisors(any(java.util.function.Consumer.class));

        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn("mock-multi-turn-response");

        ChatReply reply = service.chatWithMemory("查我最近的订单", "sess-001", null);

        assertThat(reply.content()).isEqualTo("mock-multi-turn-response");
        assertThat(reply.conversationId()).isEqualTo("sess-001");
        // 验证 advisor 拿到的 key/value 正确 (Spring AI 1.0.9 标准 key)
        assertThat(capturedKey.get()).isEqualTo("chat_memory_conversation_id");
        assertThat(capturedValue.get()).isEqualTo("sess-001");
    }

    /**
     * Plan #2: stream_returnsFluxOfContentChunks
     */
    @Test
    @DisplayName("stream() 返回 Flux<String>, 每元素非空")
    void stream_returnsFluxOfContentChunks() {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenReturn(reqSpec);
        // advisors no-op
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        when(advisorSpec.param(anyString(), any())).thenReturn(advisorSpec);
        doAnswer(inv -> {
            java.util.function.Consumer<ChatClient.AdvisorSpec> c = inv.getArgument(0);
            c.accept(advisorSpec);
            return reqSpec;
        }).when(reqSpec).advisors(any(java.util.function.Consumer.class));

        when(reqSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("您", "最近", "的", "订单"));

        Flux<String> tokens = service.stream("查我最近的订单", "sess-002", null);

        // 不用 reactor-test (避免引入新依赖), 用 Flux.collectList().block() 等价验证
        List<String> collected = tokens.collectList().block();
        assertThat(collected).isNotNull().hasSize(4);
        assertThat(collected).allSatisfy(s -> assertThat(s).isNotNull().isNotBlank());
    }

    /**
     * Plan #3: stream_withCtx_filtersToolCallbacksByStage
     * <p>跟 chat() 同款 — 流式路径 ctx 过滤不能绕过.</p>
     * <p>语义: {@code awaitingConfirmation} = L1+L2 only; L3 ({@code cancel_order}) 被过滤.</p>
     */
    @Test
    @DisplayName("stream 路径 ctx 过滤依然生效 (awaitingConfirmation 应过滤掉 L3 tool)")
    void stream_withCtx_filtersToolCallbacksByStage() {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);
        AtomicReference<List<ToolCallback>> capturedCallbacks = new AtomicReference<>();

        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenAnswer(inv -> {
            capturedCallbacks.set(inv.getArgument(0));
            return reqSpec;
        });
        ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
        when(advisorSpec.param(anyString(), any())).thenReturn(advisorSpec);
        doAnswer(inv -> {
            java.util.function.Consumer<ChatClient.AdvisorSpec> c = inv.getArgument(0);
            c.accept(advisorSpec);
            return reqSpec;
        }).when(reqSpec).advisors(any(java.util.function.Consumer.class));

        when(reqSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(Flux.just("ok"));

        // awaitingConfirmation → L1+L2 only (L3 cancel_order 被过滤)
        service.stream("hi", "sess-003",
                AuthorizationContext.awaitingConfirmation(
                        new io.github.yysf1949.rag.agent.governance.AgentIdentity(
                                "t1", "u1", "s1", java.util.Set.of("user"))));

        List<ToolCallback> callbacks = capturedCallbacks.get();
        assertThat(callbacks).isNotNull();
        assertThat(callbacks).hasSize(3); // L1×2 + L2×1 = 3, L3 已过滤
        List<String> names = callbacks.stream()
                .map(cb -> ((FunctionToolCallback<?, ?>) cb).getToolDefinition().name())
                .toList();
        assertThat(names).containsExactlyInAnyOrder("get_order", "kb_search", "create_reminder_ticket");
        assertThat(names).doesNotContain("cancel_order");
    }

    /** 跟 SpringAiAgentAdapterDynamicAuthTest / ChatClientServiceMockTest 同款 FakeDtoBean. */
    private static ToolDescriptor mockTool(String name, RiskLevel risk) {
        try {
            Method m = FakeDtoBean.class.getMethod("run", FakeDtoBean.In.class);
            return new ToolDescriptor(name, name + "_desc", risk, true, false, null,
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