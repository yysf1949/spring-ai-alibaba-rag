package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.action.InMemoryToolRegistry;
import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.builtin.KbSearchTool;
import io.github.yysf1949.rag.agent.builtin.KbSearchRequest;
import io.github.yysf1949.rag.agent.builtin.KbSearchResponse;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.StageAwareToolAuthorizer;
import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.RetrievalPort;
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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Phase 18 P0 诊断测试 — 抓 Spring AI 1.0.9 + KbSearchTool record 反序列化根因完整 stack trace.
 *
 * <p>Plan: 调 LLM 让它选 kb_search, 捕获 FunctionToolCallback invoke 失败时的
 * 完整 exception chain (不再 wrap 成 RuntimeException).</p>
 */
@EnabledIfEnvironmentVariable(named = "DEEPSEEK_API_KEY", matches = ".+")
class KbSearchDeserializeRootCauseTest {

    @Test
    void captureRootCauseException() throws Exception {
        String apiKey = System.getenv("DEEPSEEK_API_KEY");

        RetrievalPort port = mock(RetrievalPort.class);
        when(port.search(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(List.of(new RetrievedChunk(
                        "c1", "用户可在7天内退款", 0.95,
                        "default", 1L, Map.of("sourceUri", "https://x"))));

        OpenAiApi api = OpenAiApi.builder().baseUrl("https://api.deepseek.com").apiKey(apiKey).build();
        OpenAiChatModel model = OpenAiChatModel.builder().openAiApi(api)
                .defaultOptions(OpenAiChatOptions.builder().model("deepseek-chat").temperature(0.3).build())
                .build();
        ChatClient chatClient = ChatClient.create(model);

        InMemoryToolRegistry registry = new InMemoryToolRegistry();
        Field f = InMemoryToolRegistry.class.getDeclaredField("descriptors");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<String, ToolDescriptor> map = (Map<String, ToolDescriptor>) f.get(registry);
        Method m = KbSearchTool.class.getMethod("search", KbSearchRequest.class);
        map.put("kb_search", new ToolDescriptor(
                "kb_search", "知识库检索", RiskLevel.L1_READ, true, false, null, false,
                new KbSearchTool(port), m));

        StageAwareToolAuthorizer authorizer = new StageAwareToolAuthorizer(registry);
        SpringAiAgentAdapter adapter = new SpringAiAgentAdapter(registry, authorizer);
        ChatClientService service = new ChatClientService(chatClient, adapter, authorizer);

        AgentIdentity identity = new AgentIdentity("t1", "u1", "s-rootcause", Set.of("user"));
        AuthorizationContext ctx = AuthorizationContext.confirmed(identity);

        // 钩到 SpringAiAgentAdapter 内部的 FunctionToolCallback, 主动调用
        // 这样能拿到 Spring AI 内部的真实 exception (LLM 链路上异常会被吞)
        try {
            // 1. 拿 callback (Spring AI 1.0.9 用 getToolDefinition().name())
            var callbacks = adapter.getFunctionCallbacks(ctx);
            org.springframework.ai.tool.function.FunctionToolCallback<?, ?> kbCb = null;
            for (var cb : callbacks) {
                String name = (String) cb.getClass().getMethod("getToolDefinition").invoke(cb)
                        .getClass().getMethod("name").invoke(cb.getClass().getMethod("getToolDefinition").invoke(cb));
                if (name.equals("kb_search")) {
                    kbCb = cb;
                    break;
                }
            }
            if (kbCb == null) {
                System.out.println("[ROOT-CAUSE-TEST] No kb_search callback found");
                return;
            }
            // 2. 手工构造模拟 LLM 拼出的 JSON (DeepSeek 6 字段 + kbVersion=-1)
            String mockJson = "{\"tenantId\":\"t1\",\"kbId\":\"default\",\"kbVersion\":-1,\"query\":\"退款\",\"topK\":5,\"tags\":[]}";
            System.out.println("[ROOT-CAUSE-TEST] 直接调 kbSearchTool callback, input: " + mockJson);
            String result = kbCb.call(mockJson);
            System.out.println("[ROOT-CAUSE-TEST] callback result: " + result);
        } catch (Throwable t) {
            // 抓完整 chain
            System.out.println("[ROOT-CAUSE-TEST] CAUGHT: " + t.getClass().getName() + ": " + t.getMessage());
            Throwable cur = t;
            int depth = 0;
            while (cur != null && depth < 10) {
                System.out.println("[ROOT-CAUSE-TEST]   [" + depth + "] " + cur.getClass().getName() + ": " + cur.getMessage());
                StackTraceElement[] st = cur.getStackTrace();
                if (st.length > 0) {
                    for (int i = 0; i < Math.min(5, st.length); i++) {
                        System.out.println("[ROOT-CAUSE-TEST]     at " + st[i]);
                    }
                }
                cur = cur.getCause();
                depth++;
            }
        }
    }
}