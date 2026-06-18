package io.github.yysf1949.rag.agent.action;

import java.lang.reflect.Method;

/**
 * 工具元数据 — 由 {@code ToolRegistry} 在启动时扫描 {@code @ToolSpec} 注解生成。
 *
 * <p>业务侧（编排层、治理层）只依赖此 record，不直接接触反射 Method。
 * 升级 Spring AI 2.0 时，由 {@code SpringAiAgentAdapter} 负责
 * 把 {@code ToolDescriptor} 翻译成 {@code FunctionCallback}，业务侧无感。</p>
 *
 * @param name           kebab-case 工具名
 * @param description    详细描述（来自 @ToolSpec.description）
 * @param riskLevel      风险分级
 * @param idempotent     工具本身是否幂等
 * @param requiresIdempotencyKey 是否强制 idempotencyKey
 * @param bean           Spring bean 实例
 * @param method         反射 Method（参数类型是业务侧 DTO，参数顺序由声明决定）
 */
public record ToolDescriptor(
        String name,
        String description,
        RiskLevel riskLevel,
        boolean idempotent,
        boolean requiresIdempotencyKey,
        Object bean,
        Method method
) {

    /**
     * Spring AI 1.0.9 的 FunctionCallback 不支持复杂对象参数，故此约束：
     * 工具方法必须有且仅有一个参数，且参数类型是简单 DTO（POJO + 标准 getter）。
     * 该校验在 ToolRegistry 扫描时执行。
     */
    public void validate() {
        if (method.getParameterCount() != 1) {
            throw new IllegalStateException(String.format(
                    "Tool [%s] method must accept exactly 1 parameter, got %d",
                    name, method.getParameterCount()));
        }
        if (method.getReturnType() == void.class) {
            throw new IllegalStateException(String.format(
                    "Tool [%s] method must have a return type (void is not allowed)", name));
        }
    }

    public Object invoke(Object request) throws Exception {
        return method.invoke(bean, request);
    }
}