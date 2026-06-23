package io.github.yysf1949.rag.agent.action;

import java.util.List;

/**
 * 工具注册中心 — 编排层调用 {@code get(name)} 拿到 {@code ToolDescriptor} 后执行。
 *
 * <h2>生命周期</h2>
 * <ol>
 *   <li>Spring 启动 → {@code InMemoryToolRegistry.scanFromContext(ctx)} 扫描所有
 *       {@code @ToolSpec} 方法</li>
 *   <li>业务调用 {@code agentService.execute(req)} → 编排层通过
 *       {@code registry.get(name)} 拿 descriptor</li>
 *   <li>治理层（risk gate / idempotency / audit）校验通过后 → 反射调用</li>
 * </ol>
 */
public interface ToolRegistry {

    /** 扫描 Spring 上下文中的 {@code @ToolSpec} 方法。 */
    void scanFromContext(org.springframework.context.ApplicationContext ctx);

    /** 列出所有已注册工具名。 */
    List<String> listNames();

    /** 按名称获取描述符，不存在抛 {@link io.github.yysf1949.rag.agent.exception.ToolNotFoundException}。 */
    ToolDescriptor get(String name);
}