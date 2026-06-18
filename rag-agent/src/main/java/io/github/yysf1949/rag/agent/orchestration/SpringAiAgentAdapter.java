package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import io.github.yysf1949.rag.agent.governance.AuthorizationContext;
import io.github.yysf1949.rag.agent.governance.ToolAuthorizer;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.stereotype.Component;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Spring AI 1.0.9 适配器 — 把 {@code ToolDescriptor} 翻译成
 * {@code FunctionToolCallback} 数组，供 Spring AI 1.0.9 的 ChatClient 调用。
 *
 * <h2>1.0.9 vs 2.0 API 差异</h2>
 * <ul>
 *   <li>1.0.9: {@code FunctionToolCallback.builder(name, Function<I,O>).description(...).inputType(...).build()}</li>
 *   <li>2.0: {@code FunctionCallbackWrapper.builder(fn).withName(...).withDescription(...).build()}</li>
 * </ul>
 *
 * <h2>升级路径</h2>
 * <p>Spring AI 2.0 起 {@code @Tool} 注解成为主流 — 本类届时改为
 * 扫描 {@code @Tool} 方法生成 {@code ToolDefinition}（仍是单文件改动）。
 * 业务侧不感知。</p>
 */
@Component
public class SpringAiAgentAdapter {

    private final ToolRegistry registry;
    private final ToolAuthorizer toolAuthorizer;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public SpringAiAgentAdapter(ToolRegistry registry, ToolAuthorizer toolAuthorizer) {
        this.registry = registry;
        this.toolAuthorizer = toolAuthorizer;
    }

    /**
     * 把所有已注册 Tool 翻译成 Spring AI 1.0.9 的 {@code FunctionToolCallback}。
     *
     * <p><b>无 ctx 退化路径</b>：返回全部工具（向后兼容旧调用方）。
     * 生产 ChatClient 接入应走 {@link #getFunctionCallbacks(AuthorizationContext)} 显式传 ctx，
     * 让 LLM 只能看到授权范围内的 tool。</p>
     */
    public FunctionToolCallback[] getFunctionCallbacks() {
        return getFunctionCallbacks(AuthorizationContext.permissive());
    }

    /**
     * 根据 {@link AuthorizationContext} 过滤 + 翻译。
     *
     * <h2>对齐「路条编程」文章 §4 渐进式工具授权</h2>
     * <p>用户"询问规则"阶段传 awaitingConfirmation ctx → LLM 只看到 L1 工具;
     * 用户"已确认"阶段传 confirmed ctx → 加上 L2/L3。L4 工具仍由人工触发, 不进入 callback 数组。</p>
     */
    public FunctionToolCallback[] getFunctionCallbacks(AuthorizationContext ctx) {
        List<String> allowed = toolAuthorizer.authorizedTools(ctx, registry.listNames());
        List<FunctionToolCallback> out = new ArrayList<>(allowed.size());
        for (String name : allowed) {
            ToolDescriptor desc = registry.get(name);
            // SpringAiFunctionImpl 已是 Function<Object,Object> — Spring AI 1.0.9 内部反/序列化
            SpringAiFunctionImpl fn = new SpringAiFunctionImpl(desc);
            // inputType: 用 record 实际类型, Spring AI 用它反序列化 LLM JSON
            Type inputType = desc.method().getGenericParameterTypes()[0];
            FunctionToolCallback<?, ?> cb = FunctionToolCallback.builder(name, fn)
                    .description(desc.description())
                    .inputType(inputType)
                    .build();
            out.add(cb);
        }
        return out.toArray(new FunctionToolCallback[0]);
    }

    /**
     * 不走 ChatClient 的直接调用入口 — 单测和后续 e2e test 用。
     */
    public String invoke(String toolName, String requestJson) {
        ToolDescriptor desc = registry.get(toolName);
        try {
            Class<?> reqType = desc.method().getParameterTypes()[0];
            Object req = objectMapper.readValue(requestJson, reqType);
            Object result = desc.invoke(req);
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Bad request JSON for tool " + toolName, e);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Tool invocation failed: " + e.getMessage(), e);
        }
    }

    /**
     * Spring AI 1.0.9 {@code FunctionCallback} 需要的 Function impl.
     *
     * <h2>为什么是 {@code Function<Object, Object>} 而不是 {@code Function<String, String>}</h2>
     * <p>Phase 18 P0 修 Spring AI 1.0.9 + KbSearchRequest 反序列化阻塞.</p>
     * <p>{@code FunctionToolCallback.call(json)} 内部用 {@code JsonParser.fromJson(json, toolInputType)}
     * 已经把 JSON 反序列化成实际 record (KbSearchRequest), 再调 fn. 所以 fn 的输入是 record 实例
     * 不是 JSON 字符串. 输出 Spring AI 内部用 {@code toolCallResultConverter.convert(result, type)}
     * 自己序列化, fn 只需返回真实 result 即可.</p>
     *
     * <p>如果 fn 声明为 {@code Function<String, String>}, Spring AI 1.0.9 的 lambda$builder$0
     * 调 {@code fn.apply(input)} 时, 编译器插入的 checkcast 会把 record 强转成 String → ClassCastException.</p>
     *
     * <p>改 {@code Function<Object, Object>} 之后, Spring AI 拿到 record 实例直接传, fn.invoke
     * 拿到 record 直接调工具, Spring AI 内部序列化结果, 全链路不再做 String 强转.</p>
     */
    private record SpringAiFunctionImpl(ToolDescriptor descriptor)
            implements Function<Object, Object> {

        @Override
        public Object apply(Object input) {
            // input 已经被 Spring AI 1.0.9 FunctionToolCallback 反序列化成 record
            // (via JsonParser.fromJson(json, toolInputType) — toolInputType 在 builder 设的)
            try {
                return descriptor.invoke(input);
            } catch (Exception e) {
                throw new RuntimeException("Tool invoke failed: " + e.getMessage(), e);
            }
        }
    }
}