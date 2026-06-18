package io.github.yysf1949.rag.agent.governance;

import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * 工具级限流 — Resilience4j @RateLimiter 包装。
 *
 * <h2>对齐「路条编程」文章 §"评估指标要变"</h2>
 * <p>错误执行率之一是"工具被高频调用导致下游服务雪崩" — 限流把"被滥用"的工具
 * 单独隔离，不影响其他工具。</p>
 *
 * <h2>每工具独立限流器</h2>
 * <p>用 {@code registry.rateLimiter(toolName)} 给每个工具分配独立计数器。
 * 默认配置从 {@code application.yml} 读取，缺省 100 QPS / 工具。</p>
 */
@Component
public class AgentRateLimiter {

    private final RateLimiterRegistry registry;

    public AgentRateLimiter(RateLimiterRegistry registry) {
        this.registry = registry;
    }

    /**
     * 在限流器保护下执行 supplier。
     *
     * @param toolName  工具名（每个工具独立限流器）
     * @param action    要执行的业务逻辑
     * @return action 的返回值
     * @throws io.github.resilience4j.ratelimiter.RequestNotPermitted 限流触发
     */
    public <T> T execute(String toolName, Supplier<T> action) {
        RateLimiter limiter = registry.rateLimiter(toolName);
        return RateLimiter.decorateSupplier(limiter, action).get();
    }
}