package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.governance.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * Agent 入口 traceId 注入 — 5 层架构 §"可观测要端到端串联" 的物理起点。
 *
 * <h2>对齐「路条编程」文章 §"可观测要端到端串联"</h2>
 * <p>HTTP 请求进来时，从 {@code X-Agent-Trace-Id} header 读 traceId；缺失则生成 UUID
 * 作为兜底（生产可换时间有序 UUID / Snowflake ID）。然后塞进 {@link TraceContext} + SLF4J {@link MDC}，
 * 让本线程所有日志、{@link io.github.yysf1949.rag.agent.governance.ToolAuditBridge}、
 * {@link io.github.yysf1949.rag.agent.governance.AgentMetrics} 都自动带上。</p>
 *
 * <h2>Filter 顺序</h2>
 * <p>{@link Ordered#HIGHEST_PRECEDENCE + 10}，在 TenantFilter / 业务 filter 之前，
 * 保证下游所有组件都能读到 traceId。</p>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Agent-Trace-Id";
    public static final String MDC_KEY = "traceId";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        try (TraceContext.Scope ignored = TraceContext.enter(traceId)) {
            MDC.put(MDC_KEY, traceId);
            response.setHeader(HEADER, traceId);
            try {
                filterChain.doFilter(request, response);
            } finally {
                MDC.remove(MDC_KEY);
            }
        }
    }
}