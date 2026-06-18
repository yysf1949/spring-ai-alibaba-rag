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
            // SpringAiFunctionImpl 把 JSON 字符串反序列化成工具方法的入参 DTO，再 invoke
            SpringAiFunctionImpl fn = new SpringAiFunctionImpl(desc, objectMapper);
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

    /** Spring AI 1.0.9 {@code FunctionCallback} 需要的 Function impl — 单参数。 */
    private record SpringAiFunctionImpl(ToolDescriptor descriptor, ObjectMapper mapper)
            implements Function<String, String> {

        @Override
        public String apply(String requestJson) {
            ToolDescriptor desc = descriptor;
            try {
                Class<?> reqType = desc.method().getParameterTypes()[0];
                Object req = mapper.readValue(requestJson, reqType);
                Object result = desc.invoke(req);
                return mapper.writeValueAsString(result);
            } catch (Exception e) {
                throw new RuntimeException("Function apply failed: " + e.getMessage(), e);
            }
        }
    }
}