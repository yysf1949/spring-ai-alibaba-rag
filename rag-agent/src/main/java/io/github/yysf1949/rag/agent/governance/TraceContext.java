package io.github.yysf1949.rag.agent.governance;

/**
 * Trace 上下文 — 每次 Agent 会话 / 工具调用的串联 ID。
 *
 * <h2>对齐「路条编程」文章 §"可观测要端到端串联"</h2>
 * <p>"traceId 串模型/工具/业务/DB/MQ"。本类用 {@link ThreadLocal} 把 traceId 绑到当前线程，
 * 让 {@link ToolAuditBridge} / {@link AgentMetrics} / log4j MDC 都读同一个值，无需改方法签名。</p>
 *
 * <h2>生命周期</h2>
 * <pre>
 *   try (TraceContext.Scope s = TraceContext.enter("trace-abc")) {
 *       // 业务逻辑 —— audit / metrics / log 自动带上 traceId
 *   } // 退出 scope 自动 clear，避免 ThreadLocal 泄漏到下一个请求
 * </pre>
 *
 * <h2>约束</h2>
 * <ul>
 *   <li>线程隔离：每个线程独立 traceId</li>
 *   <li>不可继承：子线程不自动继承（生产可换 InheritableThreadLocal / TransmittableThreadLocal）</li>
 *   <li>空安全：未 enter 时 {@link #current()} 返回 {@code null}</li>
 * </ul>
 */
public final class TraceContext {

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() { }

    /** 进入 scope 并返回 holder — try-with-resources 自动 clear。 */
    public static Scope enter(String traceId) {
        String previous = CURRENT.get();
        CURRENT.set(traceId);
        return new Scope(previous);
    }

    /** 当前线程 traceId，未 enter 时返回 {@code null}。 */
    public static String current() {
        return CURRENT.get();
    }

    /** 直接清除（一般通过 Scope.close 调用）。 */
    public static void clear() {
        CURRENT.remove();
    }

    /** try-with-resources 持有 previous,close 时还原（支持嵌套）。 */
    public static final class Scope implements AutoCloseable {
        private final String previous;
        private boolean closed = false;

        private Scope(String previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            if (previous == null) {
                CURRENT.remove();
            } else {
                CURRENT.set(previous);
            }
        }
    }
}