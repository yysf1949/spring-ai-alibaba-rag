package io.github.yysf1949.rag.agent.action;

import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版 {@code ToolRegistry} — 启动时扫描，运行时无锁查表。
 *
 * <p>选型理由：本项目 Tool 数量在两位数以内，启动一次性扫描足够。
 * 未来 Tool 上百可换 {@code Caffeine} 或扫库 DB。</p>
 */
@Component
public class InMemoryToolRegistry implements ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(InMemoryToolRegistry.class);

    private final Map<String, ToolDescriptor> descriptors = new ConcurrentHashMap<>();

    @Override
    public void scanFromContext(ApplicationContext ctx) {
        Map<String, Object> beans = ctx.getBeansWithAnnotation(Component.class);
        for (Object bean : beans.values()) {
            for (Method m : bean.getClass().getMethods()) {
                ToolSpec spec = m.getAnnotation(ToolSpec.class);
                if (spec == null) continue;
                ToolDescriptor desc = new ToolDescriptor(
                        spec.name(),
                        spec.description(),
                        spec.riskLevel(),
                        spec.idempotent(),
                        spec.requiresIdempotencyKey(),
                        spec.maxAmountCents() >= 0 ? spec.maxAmountCents() : null,
                        bean,
                        m);
                desc.validate();
                if (descriptors.containsKey(spec.name())) {
                    throw new IllegalStateException("Duplicate tool name: " + spec.name());
                }
                descriptors.put(spec.name(), desc);
                log.info("Registered tool [{}] riskLevel={} bean={} method={}",
                        spec.name(), spec.riskLevel(), bean.getClass().getSimpleName(), m.getName());
            }
        }
        log.info("ToolRegistry scan complete: {} tools registered", descriptors.size());
    }

    @Override
    public List<String> listNames() {
        return new ArrayList<>(descriptors.keySet());
    }

    @Override
    public ToolDescriptor get(String name) {
        ToolDescriptor d = descriptors.get(name);
        if (d == null) {
            throw new ToolNotFoundException(name);
        }
        return d;
    }
}