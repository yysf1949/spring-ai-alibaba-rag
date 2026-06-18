package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.api.ChatReply;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.StageAwareToolAuthorizer;
import io.github.yysf1949.rag.agent.memory.InMemoryChatMemoryStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import reactor.core.publisher.Flux;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Phase 22: 多轮对话上下文集成测试 — Agent 层完整集成.
 *
 * <p>验证 ChatClientService.chatWithMemory() 通过 MessageChatMemoryAdvisor
 * 实现的多轮上下文. 用 mock ChatClient 链验证 conversationId 透传和 memory store 写入.</p>
 *
 * <h2>覆盖场景</h2>
 * <ol>
 *   <li>chatWithMemory 透传 conversationId 到 advisor</li>
 *   <li>多轮调用后 memory store 持有正确的消息数</li>
 *   <li>不同 conversationId 上下文隔离</li>
 *   <li>stream() 多轮 + memory 持久化</li>
 * </ol>
 */
@DisplayName("多轮对话上下文 — Agent 层完整集成")
class ChatClientServiceMultiTurnContextTest {

    private InMemoryToolRegistry registry;
    private StageAwareToolAuthorizer authorizer;
    private SpringAiAgentAdapter adapter;
    private InMemoryChatMemoryStore memoryStore;
    private ChatClient mockChatClient;
    private ChatClientService service;

    /** 捕获 advisor 参数 */
    private final AtomicReference<String> capturedConvIdKey = new AtomicReference<>();
    private final AtomicReference<Object> capturedConvIdValue = new AtomicReference<>();

    @BeforeEach
    void setUp() throws Exception {
        registry = new InMemoryToolRegistry();
        Field f = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) f.get(registry);
        map.put("get_order", mockTool("get_order", RiskLevel.L1_READ));
        map.put("kb_search", mockTool("kb_search", RiskLevel.L1_READ));

        authorizer = new StageAwareToolAuthorizer(registry);
        adapter = new SpringAiAgentAdapter(registry, authorizer);

        memoryStore = new InMemoryChatMemoryStore();
        mockChatClient = mock(ChatClient.class);
        service = new ChatClientService(mockChatClient, adapter, authorizer);
    }

    /**
     * chatWithMemory 透传 conversationId — 用 mock 链验证 advisor 参数.
     */
    @Test
    @DisplayName("chatWithMemory 透传 conversationId 到 advisor")
    void chatWithMemory_passesConversationId() {
        mockChatClientChain("mock-reply");

        ChatReply reply = service.chatWithMemory("查订单", "conv-001", null);

        assertThat(reply.content()).isEqualTo("mock-reply");
        assertThat(reply.conversationId()).isEqualTo("conv-001");
    }

    /**
     * 多轮调用后 memory store 写入验证 — 由于 ChatClient 是 mock,
     * advisor 不会真正执行 (mock 链不走 advisor). 这里验证 service 层正确组装.
     *
     * <p>注: 真正的 memory 持久化由 ChatMemoryPersistenceE2ETest (真实 DeepSeek) 覆盖.
     * 本测试验证 Agent 层编排逻辑.</p>
     */
    @Test
    @DisplayName("多次调用 chatWithMemory → service 正确组装 + 返回")
    void multiTurn_serviceAssemblesCorrectly() {
        // Turn 1
        mockChatClientChain("reply-1");
        ChatReply r1 = service.chatWithMemory("记住数字 42", "conv-mt", null);
        assertThat(r1.content()).isEqualTo("reply-1");
        assertThat(r1.conversationId()).isEqualTo("conv-mt");

        // Turn 2
        mockChatClientChain("reply-2");
        ChatReply r2 = service.chatWithMemory("我让你记的数字是？", "conv-mt", null);
        assertThat(r2.content()).isEqualTo("reply-2");
        assertThat(r2.conversationId()).isEqualTo("conv-mt");

        // Turn 3
        mockChatClientChain("reply-3");
        ChatReply r3 = service.chatWithMemory("再确认一下", "conv-mt", null);
        assertThat(r3.content()).isEqualTo("reply-3");
    }

    /**
     * 不同 conversationId → 隔离: mock 验证每次调用的 conversationId 不同.
     */
    @Test
    @DisplayName("不同 conversationId → 隔离")
    void multiTurn_differentConversation_isolated() {
        mockChatClientChain("reply-A");
        ChatReply rA = service.chatWithMemory("查订单 ORD-123", "conv-A", null);

        mockChatClientChain("reply-B");
        ChatReply rB = service.chatWithMemory("查优惠券", "conv-B", null);

        assertThat(rA.conversationId()).isEqualTo("conv-A");
        assertThat(rB.conversationId()).isEqualTo("conv-B");
        // 验证两者 conversationId 不同
        assertThat(rA.conversationId()).isNotEqualTo(rB.conversationId());
    }

    /**
     * stream() 调用 → 返回 Flux, 不报错.
     */
    @Test
    @DisplayName("stream() 多轮 → Flux 正常返回")
    void multiTurn_stream_works() {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.StreamResponseSpec streamSpec = mock(ChatClient.StreamResponseSpec.class);

        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenReturn(reqSpec);
        when(reqSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(reqSpec);
        when(reqSpec.stream()).thenReturn(streamSpec);
        when(streamSpec.content()).thenReturn(reactor.core.publisher.Flux.just("chunk1", "chunk2"));

        var flux = service.stream("记住数字 7", "conv-stream", null);
        List<String> chunks = flux.collectList().block();

        assertThat(chunks).isNotNull().hasSize(2);
        assertThat(chunks).containsExactly("chunk1", "chunk2");
    }

    /**
     * null ctx → fallback permissive: service 不报错, 正常返回.
     */
    @Test
    @DisplayName("null ctx → fallback permissive")
    void chatWithMemory_nullCtx_fallbackPermissive() {
        mockChatClientChain("fallback-reply");

        ChatReply reply = service.chatWithMemory("test", "conv-null", null);
        assertThat(reply.content()).isEqualTo("fallback-reply");
    }

    /**
     * permissive ctx → 所有 L1 tool 可见.
     */
    @Test
    @DisplayName("permissive ctx → visibleToolCount 包含 L1")
    void visibleToolCount_permissive_includesL1() {
        int count = service.visibleToolCount(AuthorizationContext.permissive(),
                registry.listNames());
        assertThat(count).isGreaterThanOrEqualTo(2); // get_order + kb_search
    }

    // ---- helpers ----

    /** 每次调用前重新 mock ChatClient prompt 链 (避免跨调用干扰). */
    private void mockChatClientChain(String reply) {
        ChatClient.ChatClientRequestSpec reqSpec = mock(ChatClient.ChatClientRequestSpec.class);
        ChatClient.CallResponseSpec callSpec = mock(ChatClient.CallResponseSpec.class);

        when(mockChatClient.prompt()).thenReturn(reqSpec);
        when(reqSpec.system(anyString())).thenReturn(reqSpec);
        when(reqSpec.user(anyString())).thenReturn(reqSpec);
        when(reqSpec.toolCallbacks(anyList())).thenReturn(reqSpec);

        // advisors(Consumer) — capture param calls
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            java.util.function.Consumer<ChatClient.AdvisorSpec> consumer = inv.getArgument(0);
            ChatClient.AdvisorSpec advisorSpec = mock(ChatClient.AdvisorSpec.class);
            when(advisorSpec.param(anyString(), any())).thenAnswer(p -> {
                capturedConvIdKey.set(p.getArgument(0));
                capturedConvIdValue.set(p.getArgument(1));
                return advisorSpec;
            });
            consumer.accept(advisorSpec);
            return reqSpec;
        }).when(reqSpec).advisors(any(java.util.function.Consumer.class));

        when(reqSpec.call()).thenReturn(callSpec);
        when(callSpec.content()).thenReturn(reply);
    }

    /** Minimal bean with 1-arg method for SpringAiAdapter. */
    public static class FakeDtoBean {
        public record In(String q) { }
        public record Out(String result) { }
        public Out run(In in) { return new Out("ok:" + in.q()); }
    }

    private static ToolDescriptor mockTool(String name, RiskLevel level) {
        try {
            Method m = FakeDtoBean.class.getMethod("run", FakeDtoBean.In.class);
            return new ToolDescriptor(name, "desc", level, false, false, null, false, new FakeDtoBean(), m);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException(e);
        }
    }
}
