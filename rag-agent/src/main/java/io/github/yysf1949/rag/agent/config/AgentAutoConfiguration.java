package io.github.yysf1949.rag.agent.config;

import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.ChannelAdapter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Agent Action Layer 的启动钩子。
 *
 * <p>设计意图（见 {@link ToolRegistry}）：Spring 启动完成后必须调用
 * {@code scanFromContext(ctx)} 一次，把所有 {@code @ToolSpec} 注解的方法
 * 装入 registry。本配置类提供该钩子 — 没有它，registry 永远空，所有
 * invoke 都会 500 ToolNotFoundException。</p>
 *
 * <h2>为什么用 {@code ApplicationReadyEvent} 而不是 {@code @PostConstruct}</h2>
 * <p>Spring 6.x 默认禁止循环引用 + 严格 {@code @PostConstruct} 时机检查；
 * {@code AgentAutoConfiguration} 注入 {@code ToolRegistry}，而
 * {@code InMemoryToolRegistry} 是 {@code @Component} 也要被 Spring 创建，
 * 在 {@code @PostConstruct} 阶段 Spring 会先校验依赖闭环并失败。</p>
 *
 * <p>{@code ApplicationReadyEvent} 在整个上下文完全就绪后触发，是「应用
 * 启动 → 业务可服务」的稳定分界点，没有循环依赖问题。</p>
 *
 * <h2>为什么用 {@code ObjectProvider}</h2>
 * <p>避免在 {@code AgentAutoConfiguration} 构造期强依赖
 * {@code ToolRegistry}，让 Spring 用延迟注入方式解掉该边，进一步降低
 * 循环依赖误报的概率。</p>
 */
@Component
public class AgentAutoConfiguration {

    private static final Logger log = LoggerFactory.getLogger(AgentAutoConfiguration.class);

    private final ApplicationContext ctx;
    private final ObjectProvider<ToolRegistry> registryProvider;
    private final Map<AgentChannel, ChannelAdapter> adapters;

    public AgentAutoConfiguration(ApplicationContext ctx,
                                  ObjectProvider<ToolRegistry> registryProvider,
                                  List<ChannelAdapter> adapterList) {
        this.ctx = ctx;
        this.registryProvider = registryProvider;
        this.adapters = adapterList.stream()
                .collect(Collectors.toMap(ChannelAdapter::channel, a -> a));
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        ToolRegistry registry = registryProvider.getObject();
        registry.scanFromContext(ctx);
        log.info("AgentAutoConfiguration scan done — {} tool(s) ready: {} ({} channel(s) ready: {})",
                registry.listNames().size(), registry.listNames(),
                adapters.size(), adapters.keySet());
    }

    /** Phase 11+ 用 — 现在只是预留入口, Phase 10 没有任何 caller 调它 */
    public Optional<ChannelAdapter> adapterFor(AgentChannel channel) {
        return Optional.ofNullable(adapters.get(channel));
    }
}
