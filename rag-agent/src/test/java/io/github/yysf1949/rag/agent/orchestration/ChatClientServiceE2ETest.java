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
 * Phase 15 Task 3: 真实 DeepSeek E2E 联调测试 — 手工装配 OpenAI-compatible ChatClient, 不依赖 Spring 上下文.
 *
 * <h2>激活条件</h2>
 * <p>{@code @EnabledIfEnvironmentVariable("DEEPSEEK_API_KEY")} — CI 无 key 自动 skip (不 fail);
 * 本地有 key 时用户跑真实流量验证 ChatClient 串通 ctx 过滤是否生效.</p>
 *
 * <h2>手工装配原因</h2>
 * <p>rag-agent 是 library 模块 (无 {@code AgentApplication} 主类),
 * 不走 {@code @SpringBootTest} 自动配置路径. 这里直接用 Spring AI 1.0.9 的 Builder API:
 * <pre>
 *   OpenAiApi.builder().baseUrl(DEEPSEEK_BASE).apiKey(KEY).build()   // OpenAI-compatible 协议
 *   → OpenAiChatModel.builder().openAiApi(api).defaultOptions(...).build()  // ChatModel
 *   → ChatClient.create(model)                                       // 拿到 ChatClient
 *   → new ChatClientService(client, adapter, authorizer).chat(...)   // 触发真实 API
 * </pre>
 * 这样测试保持轻量, 不污染 rag-agent 其他测试 (Phase 10/11/12/13/14 E2E 仍用 AnnotationConfigApplicationContext).</p>
 *
 * <h2>测试粒度</h2>
 * <p>仅 1 个用例, 用最简 prompt ("ping") 验证:
 * <ol>
 *   <li>ChatClient 真能调到 DeepSeek (network 通 + key 有效 + base-url 正确)</li>
 *   <li>AuthorizationContext.confirmed() ctx 过滤后 LLM 能看到 tool</li>
 *   <li>LLM 返回非空文本响应 (说明 API 调用真通了)</li>
 * </ol></p>
 *
 * <h2>为什么 1 个用例而非 7 个</h2>
 * <p>真实 LLM 不可重复 — 同一 prompt 不同次可能选不同 tool. E2E 目的是 <b>串通验证</b>
 * 而非全功能回归. 全功能回归靠 {@code ChatClientServiceMockTest} (Task 2).</p>
 *
 * <h2>运行方式</h2>
 * <pre>
 * DEEPSEEK_API_KEY=*** mvn -pl rag-agent test -Dtest=ChatClientServiceE2ETest
 * </pre>
 *
 * <h2>安全</h2>
 * <p>API key 仅从环境变量读, 不写进任何文件/git/history.</p>
 */
@DisplayName("ChatClientService Real DeepSeek E2E")
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class ChatClientServiceE2ETest {

    private static final String DEEPSEEK_BASE_URL = "https://api.deepseek.com";
    private static final String DEEPSEEK_MODEL = "deepseek-chat";

    @Test
    @DisplayName("DeepSeek API 可达 + ctx confirmed 过滤后 LLM 看到 tool + LLM 返回非空")
    void chat_realDeepSeek_ctxFilterAndLLMResponse() throws Exception {
        // 1. 拿环境变量里的 key (无 key 时 @EnabledIf 已 skip, 不会到这)
        String apiKey = System.getenv("DEEPSEEK_API_KEY");
        assertThat(apiKey).as("DEEPSEEK_API_KEY must be set when @EnabledIf activates").isNotBlank();

        // 2. 手工装配 OpenAI-compatible ChatClient
        OpenAiApi api = OpenAiApi.builder()
                .baseUrl(DEEPSEEK_BASE_URL)
                .apiKey(apiKey)
                .build();
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .model(DEEPSEEK_MODEL)
                .temperature(0.7)
                .build();
        OpenAiChatModel model = OpenAiChatModel.builder()
                .openAiApi(api)
                .defaultOptions(options)
                .build();
        ChatClient realClient = ChatClient.create(model);

        // 3. 复用 Phase 14 ship pattern: 反射注入 7 个 tool descriptors (3 L1 + 2 L2 + 2 L3)
        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        Field f = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
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

        StageAwareToolAuthorizer authorizer = new StageAwareToolAuthorizer(registry);
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(registry, authorizer);

        // 4. ChatClientService 注入真 ChatClient
        ChatClientService service = new ChatClientService(realClient, adapter, authorizer);

        // 5. confirmed ctx: L1-L3 全开, 7 个 tool 应可见
        AgentIdentity identity = new AgentIdentity("e2e-tenant", "e2e-user", "e2e-session", Set.of("user"));
        AuthorizationContext ctx = AuthorizationContext.confirmed(identity);

        int visible = service.visibleToolCount(ctx, registry.listNames());
        assertThat(visible)
                .as("confirmed ctx 应让 LLM 看到全部 7 个 tool (L1×3 + L2×2 + L3×2)")
                .isEqualTo(7);

        // 6. 真发 DeepSeek: 最简 prompt, 不期望 LLM 选 tool (它会回一句 "pong" 类似)
        String response = service.chat("ping", ctx);

        // 7. 验证: LLM 返回非空 (DeepSeek API 真通了)
        assertThat(response)
                .as("DeepSeek API 必须返回非空 content (否则 base-url/key/model 有问题)")
                .isNotBlank();

        // 8. 日志: 让用户在 surefire 报告里看到响应 (debug 用)
        System.out.println("[ChatClientServiceE2ETest] DeepSeek 响应: " + response);
        System.out.println("[ChatClientServiceE2ETest] visible tools: " + visible);
    }

    /** 跟 SpringAiAgentAdapterDynamicAuthTest / ChatClientServiceMockTest 同款 FakeDtoBean. */
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

    /** 给测试实例字段访问 List 类型的辅助 (不引入新依赖) */
    @SuppressWarnings("unused")
    private static List<String> asList(String... s) { return List.of(s); }
}