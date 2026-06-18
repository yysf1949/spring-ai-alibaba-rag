package io.github.yysf1949.rag.agent.orchestration;

import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.ToolAuthorizer;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Objects;

/**
 * Phase 15 Task 2: LLM 对话服务 — 接收用户消息 + AuthorizationContext,
 * 用 ctx 过滤后的 Function Callbacks 调 ChatClient (OpenAI-compatible DeepSeek backend).
 *
 * <h2>与既有链路的关系</h2>
 * <p>本服务是 <b>平行入口</b>, 不替换 {@code AgentController} + {@code DefaultAgentLoop}.
 * 既有的"用户发问题 → AgentController → DefaultAgentLoop → Tool 反射调用"链路保持不变;
 * ChatClientService 提供新的"用户发问题 → ChatClient → LLM 自动选 Tool"路径, 用于:
 * <ol>
 *   <li>Phase 15 真实 E2E 联调验证 AuthorizationContext 过滤效果</li>
 *   <li>Phase 16+ AgentController 双链路接入</li>
 *   <li>Phase 16+ SSE 流式响应</li>
 * </ol></p>
 *
 * <h2>AuthorizationContext 串通</h2>
 * <p>核心创新: 复用 Phase 14 ship 的 {@link SpringAiAgentAdapter#getFunctionCallbacks(AuthorizationContext)},
 * 让 LLM 看到的 tool 列表严格受 ctx 过滤 (Stage 1 只 L1, Stage 2 L1+L2, Stage 3 L1-L3).
 * Authorizer 是 Phase 14 的 {@link StageAwareToolAuthorizer} — LLM 既"看不到"也被 RiskGate 双重防护.</p>
 *
 * <h2>启动条件</h2>
 * <ul>
 *   <li>{@link ConditionalOnBean @ConditionalOnBean(ChatClient.class)} — 默认 profile 没 ChatClient Bean (yml 不激活), 不创建
 *       → 测试可注入 mock ChatClient 而无需真实 API key</li>
 *   <li>生产需激活 {@code deepseek} profile (见 {@link DeepSeekChatClientConfig})</li>
 * </ul>
 */
@Service
@ConditionalOnBean(ChatClient.class)
public class ChatClientService {

    /**
     * 系统 prompt — 故意简短, 让 LLM 自然选 tool;
     * 严格风控交给 AuthorizationContext + StageAwareToolAuthorizer.
     */
    public static final String SYSTEM_PROMPT = """
            你是企业 AI 客服助手. 根据用户问题选择最合适的工具调用.
            工具按风险级分层: L1=只读, L2=可撤回, L3=业务态变更.
            只调用当前阶段授权范围内的工具.
            """;

    private final ChatClient chatClient;
    private final SpringAiAgentAdapter adapter;
    private final ToolAuthorizer toolAuthorizer;

    public ChatClientService(ChatClient chatClient,
                             SpringAiAgentAdapter adapter,
                             ToolAuthorizer toolAuthorizer) {
        this.chatClient = Objects.requireNonNull(chatClient, "chatClient");
        this.adapter = Objects.requireNonNull(adapter, "adapter");
        this.toolAuthorizer = Objects.requireNonNull(toolAuthorizer, "toolAuthorizer");
    }

    /**
     * 对话入口 — 让 LLM 看到 ctx 授权范围内的工具, 自然调用.
     *
     * @param userMessage 用户原始消息
     * @param ctx         本次请求授权上下文; null → 退化到 {@link AuthorizationContext#permissive()}
     * @return LLM 文本响应 (AssistantMessage.getText)
     */
    public String chat(String userMessage, AuthorizationContext ctx) {
        Objects.requireNonNull(userMessage, "userMessage");
        AuthorizationContext effective = (ctx != null) ? ctx : AuthorizationContext.permissive();

        // 1. ctx 过滤 → LLM 看到的 tool 子集
        // 注意: SpringAiAgentAdapter.getFunctionCallbacks(ctx) 返回 FunctionToolCallback[]
        // (数组协变限制: 不能直接转 ToolCallback[]), 用 Arrays.asList 桥接到 List<ToolCallback>
        FunctionToolCallback[] fnCallbacks = adapter.getFunctionCallbacks(effective);
        List<ToolCallback> callbacks = new java.util.ArrayList<>(fnCallbacks.length);
        for (FunctionToolCallback cb : fnCallbacks) callbacks.add(cb);

        // 2. ChatClient call (blocking, Phase 16 再加 stream)
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(userMessage)
                .toolCallbacks(callbacks)
                .call()
                .content();
    }

    /**
     * 给上层 (Phase 16+ AgentController / 多轮对话) 用的真实 count.
     */
    public int visibleToolCount(AuthorizationContext ctx, List<String> allTools) {
        AuthorizationContext effective = (ctx != null) ? ctx : AuthorizationContext.permissive();
        return toolAuthorizer.authorizedTools(effective, allTools).size();
    }
}