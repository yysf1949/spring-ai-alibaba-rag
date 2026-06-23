package io.github.yysf1949.rag.agent.action;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;

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
 * @param maxAmountCents L3 写操作工具的金额上限（分）；null/-1 表示不限
 * @param bean           Spring bean 实例
 * @param method         反射 Method（参数类型是业务侧 DTO，参数顺序由声明决定）
 */
public record ToolDescriptor(
        String name,
        String description,
        RiskLevel riskLevel,
        boolean idempotent,
        boolean requiresIdempotencyKey,
        Long maxAmountCents,
        boolean requiresConfirmationToken,
        Object bean,
        Method method
) {

    /**
     * 工具方法签名校验 — 允许 1-3 个参数。
     *
     * <h2>参数位置约定（编排层按类型注入）</h2>
     * <ol>
     *   <li>参数 0（可选）: {@link AgentIdentity} — 编排层注入调用者身份</li>
     *   <li>参数 1（可选）: {@link IdempotencyKey} — 编排层注入幂等键（L2+ 写操作必传）</li>
     *   <li>最后一个参数（必传）: 业务 DTO — POJO/record，非 primitive / String / 特殊治理类型</li>
     * </ol>
     *
     * <p>工具 <em>可以</em> 只声明最后一个参数（最简形态，1 个参数，向后兼容）。</p>
     *
     * <h2>Spring AI 1.0.9 {@code FunctionCallback} 单参数约束</h2>
     * <p>{@code SpringAiAgentAdapter} 走的是 Spring AI 1.0.9 {@code FunctionToolCallback}，
     * 它的 {@code Function<I,O>} 接口只允许单参数 — 故 {@link #invoke(Object)} 仅把
     * {@code request} 传给业务 DTO 位置。如果工具声明 3 个参数（含 {@code AgentIdentity} /
     * {@code IdempotencyKey}），Spring AI 1.0.9 路径 <b>不</b>适用，只能走
     * {@code AgentLoop.execute(AgentRequest)}（编排层显式调用）。</p>
     */
    public void validate() {
        Class<?>[] params = method.getParameterTypes();
        if (params.length < 1 || params.length > 3) {
            throw new IllegalStateException(String.format(
                    "Tool [%s] must accept 1-3 parameters (AgentIdentity, IdempotencyKey, Request), got %d",
                    name, params.length));
        }
        if (method.getReturnType() == void.class) {
            throw new IllegalStateException(String.format(
                    "Tool [%s] method must have a return type (void is not allowed)", name));
        }
        // 最后一个参数（业务 DTO）必须是 POJO，非 primitive / String / 特殊治理类型
        Class<?> businessParam = params[params.length - 1];
        if (businessParam.isPrimitive() || businessParam == String.class
                || businessParam == AgentIdentity.class || businessParam == IdempotencyKey.class) {
            throw new IllegalStateException(String.format(
                    "Tool [%s] last parameter must be a business DTO, got %s", name, businessParam.getName()));
        }
    }

    public Object invoke(Object request) throws Exception {
        return method.invoke(bean, request);
    }
}