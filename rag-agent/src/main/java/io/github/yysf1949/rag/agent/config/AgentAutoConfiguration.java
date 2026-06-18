package io.github.yysf1949.rag.agent.config;

import io.github.yysf1949.rag.agent.action.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Configuration;

/**
 * Agent Action Layer 的启动钩子。
 *
 * <p>设计意图（见 {@link ToolRegistry} 注释）：Spring 启动完成后必须
 * 调用 {@code scanFromContext(ctx)} 一次，把所有 {@code @ToolSpec} 注解
 * 的方法装入 registry。本配置类提供该钩子 — 没有它，registry 永远空，
 * 所有 invoke 都会 500 ToolNotFoundException。</p>
 *
 * <p>为什么用 {@code @PostConstruct}：{@code scanFromContext} 必须在所有
 * {@code @Component} bean 完成依赖注入后调用，而 {@code @PostConstruct}
 * 在依赖注入完成后、{@code @EventListener(ApplicationReadyEvent)} 之前执行，
 * 保证 scan 完后整个 registry 对所有 controller 可见。</p>
 */
@Configuration
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    private final ApplicationContext ctx;
    private final ToolRegistry registry;

    public AgentAutoConfiguration(ApplicationContext ctx, ToolRegistry registry) {
        this.ctx = ctx;
        this.registry = registry;
    }

    @PostConstruct
    void scanTools() {
        registry.scanFromContext(ctx);
        log.info("AgentAutoConfiguration.scanTools done — {} tool(s) ready", registry.listNames().size());
    }
}
