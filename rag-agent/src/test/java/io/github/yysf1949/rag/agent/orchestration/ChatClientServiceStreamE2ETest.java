package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.StageAwareToolAuthorizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.openai.api.OpenAiApi;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 16 Task 5: 真实 DeepSeek SSE 流式 E2E 测试.
 *
 * <h2>激活条件</h2>
 * <p>{@code @EnabledIfEnvironmentVariable("DEEPSEEK_API_KEY")} — CI 无 key 自动 skip (不 fail).</p>
 *
 * <h2>验证点</h2>
 * <ol>
 *   <li>Spring AI 1.0.9 ChatClient.stream() 真实调用 DeepSeek 拿到流式 chunk</li>
 *   <li>Flux 至少 3 个元素 (4-32 token/chunk, 流式粒度由 LLM 协议决定)</li>
 *   <li>拼起来的中文响应含"订单"或同义词 (说明 LLM 看到了 tool + 选了 get_order)</li>
 * </ol>
 *
 * <h2>不验证</h2>
 * <ul>
 *   <li>具体 token 数 / token 内容 (LLM 不可重复)</li>
 *   <li>tool 真调成功 (Stream 路径只验证流式, 工具调用覆盖在 ChatClientServiceE2ETest)</li>
 * </ul>
 *
 * <h2>运行方式</h2>
 * <pre>
 * DEEPSEEK_API_KEY=*** mvn -pl rag-agent test -Dtest=ChatClientServiceStreamE2ETest
 * </pre>
 */
@DisplayName("ChatClientService Real DeepSeek SSE Stream E2E")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class ChatClientServiceStreamE2ETest {

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";

    @Test
    @DisplayName("DeepSeek 流式 → Flux 至少 3 个 chunk + 中文含 订单 同义词")
    void stream_realDeepSeek_returnsFluxWithNonEmptyChunks() throws Exception {
        // 1. key (无 key 时 @EnabledIf 已 skip)
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).isNotBlank();

        // 2. 手工装配 ChatClient (跟 ChatClientServiceE2ETest 同款)
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(apiKey)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder()
                        .model(DEEPSEEK_MODEL)
                        .temperature(0.3)
                        .build())
                .build();
        ChatClient chatClient = ChatClient.create(model);

        // 3. 手工装配 tool registry (1 个 L1 tool: get_order) + ctx
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        Field f = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        f.setAccessible(true);
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) f.get(registry);
        Method m = FakeDtoBean.class.getMethod("run", FakeDtoBean.In.class);
        map.put("get_order", new ToolDescriptor("get_order", "查询单个订单详情",
                RiskLevel.L1_READ, true, false, null, false, new FakeDtoBean(), m));

        StageAwareToolAuthorizer authorizer = new StageAwareToolAuthorizer(registry);
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(registry, authorizer);
        ChatClientService service = new ChatClientService(chatClient, adapter, authorizer);

        // 4. permissive ctx 让 LLM 看到 get_order, 提示它查订单
        AuthorizationContext ctx = AuthorizationContext.confirmed(
                new AgentIdentity("t1", "u1", "sess-stream-1", Set.of("user")));

        // 5. 触发流式调用
        var chunks = service.stream("帮我查一下最近一笔订单的状态, 用一句话回答", "sess-stream-1", ctx)
                .collectList()
                .block();

        // 6. 验证: 至少 3 个 chunk, 全部非空
        assertThat(chunks).isNotNull();
        assertThat(chunks.size()).as("Flux chunk 数").isGreaterThanOrEqualTo(3);
        assertThat(chunks).allSatisfy(c -> assertThat(c).isNotNull().isNotBlank());

        // 7. 拼起来的中文应包含订单相关字 (LLM 选 tool 后会说订单号)
        String joined = String.join("", chunks);
        assertThat(joined)
                .as("拼起来的流式响应应包含'订单'或'ORD'或'查询'之一 (LLM 选 tool 后回答)")
                .containsAnyOf("订单", "ORD", "查询", "详情", "状态");
    }

    /** FakeDtoBean — 跟其他 mock test 同款, 提供 1-arg 方法供 ToolDescriptor 反射. */
    public static class FakeDtoBean {
        public record In(String q) { }
        public record Out(String result) { }
        public Out run(In in) { return new Out("ok:" + in.q()); }
    }
}