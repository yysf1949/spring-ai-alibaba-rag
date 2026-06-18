package io.github.yysf1949.rag.agent.governance;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 租户级 QPS 限流 — 防单租户霸占全局资源。
 *
 * <h2>对齐「路条编程」文章 §"评估指标要变"</h2>
 * <p>"错误执行率" 一个来源是单租户把 Agent 后端资源耗光。本类给每租户独立
 * Resilience4j RateLimiter，触发时抛 {@link TenantRateLimitedException}。</p>
 *
 * <h2>与已有 {@link AgentRateLimiter} 的关系</h2>
 * <ul>
 *   <li>{@link AgentRateLimiter} — 工具级（按 toolName 限流）</li>
 *   <li>本类 — 租户级（按 tenantId 限流，防一个租户霸占全部工具）</li>
 * </ul>
 *
 * <h2>懒加载 + 配置可调</h2>
 * <p>每租户独立 {@code RateLimiter}，缺省 50 QPS / 租户；可通过
 * {@link TenantRateLimiter#setDefaultQps(int)} 调全局缺省值，也可调用
 * {@link TenantRateLimiter#setQps(String, int)} 单独调整某租户。</p>
 */
@Component
public class TenantRateLimiter {

    private final RateLimiterRegistry registry;
    private volatile int defaultQps = 50;

    public TenantRateLimiter() {
        this.registry = RateLimiterRegistry.of(defaultConfig(50));
    }

    /** Spring 注入用：传入共享 registry。 */
    public TenantRateLimiter(RateLimiterRegistry registry) {
        this.registry = registry;
    }

    /** 调整全局缺省 QPS — 影响后续新建的租户限流器（已存在的不变）。 */
    public void setDefaultQps(int qps) {
        this.defaultQps = qps;
    }

    /** 给某租户单独配置 QPS — 重新创建 limiter 覆盖旧配置。 */
    public void setQps(String tenantId, int qps) {
        RateLimiter limiter = RateLimiter.of(tenantId, defaultConfig(qps));
        if (registry.find(tenantId).isPresent()) {
            registry.replace(tenantId, limiter);
        } else {
            // 首次注册 — 通过 find-后-create 模式直接落入 registry
            registry.rateLimiter(limiter.getName());
            // 上面的注册是"查找"语义，再 replace 一定生效
            registry.replace(tenantId, limiter);
        }
    }

    /**
     * 在租户级限流保护下执行 supplier。
     *
     * @throws TenantRateLimitedException 限流触发时
     */
    public <T> T execute(String tenantId, java.util.function.Supplier<T> action) {
        // 先 ensure limiter 存在（用当前 defaultQps），再调用不带第二参数以复用注册项
        if (!registry.find(tenantId).isPresent()) {
            registry.rateLimiter(tenantId, defaultConfig(defaultQps));
        }
        RateLimiter limiter = registry.rateLimiter(tenantId);
        try {
            return RateLimiter.decorateSupplier(limiter, action).get();
        } catch (io.github.resilience4j.ratelimiter.RequestNotPermitted e) {
            throw new TenantRateLimitedException(tenantId, e.getMessage());
        }
    }

    private static RateLimiterConfig defaultConfig(int qps) {
        return RateLimiterConfig.custom()
                .limitForPeriod(qps)
                .limitRefreshPeriod(Duration.ofSeconds(1))
                .timeoutDuration(Duration.ZERO) // 不排队，立即拒绝
                .build();
    }
}