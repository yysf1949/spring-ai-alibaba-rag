package io.github.yysf1949.rag.agent.api;

/**
 * Agent 调用的最终状态。
 *
 * <h2>状态分类</h2>
 * <ul>
 *   <li><b>终态</b>（isTerminal=true）：Agent 这一轮不再继续处理</li>
 *   <ul>
 *     <li>{@link #SUCCESS} — 工具执行成功</li>
 *     <li>{@link #FAILURE} — 工具执行失败（异常/超时）</li>
 *     <li>{@link #DENIED} — 风险门控拒绝（L2+ 缺幂等键/L4 非 admin 等）</li>
 *     <li>{@link #REPLAY} — 幂等键命中，复用上次结果</li>
 *   </ul>
 *   <li><b>非终态</b>（isTerminal=false）：需要后续步骤</li>
 *   <ul>
 *     <li>{@link #HANDOFF_REQUIRED} — Agent 转人工（人工接着处理）</li>
 *   </ul>
 * </ul>
 *
 * <h2>与文章的对应</h2>
 * <p>对齐「路条编程」AI 客服文章 §"人工确认不是失败" — 转人工不是 Agent 失败，
 * 而是"自动处理低风险任务，辅助处理高风险任务"分工的一部分。</p>
 */
public enum AgentOutcome {
    SUCCESS(true),
    FAILURE(true),
    DENIED(true),
    REPLAY(true),
    HANDOFF_REQUIRED(false);

    private final boolean terminal;

    AgentOutcome(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isTerminal() {
        return terminal;
    }

    /** 容错解析 — 接受 SUCCESS / success / SUCCESS_OR_xxx（取前缀匹配） */
    public static AgentOutcome fromString(String s) {
        if (s == null) {
            throw new IllegalArgumentException("outcome string is null");
        }
        String normalized = s.toUpperCase().replace('-', '_');
        for (AgentOutcome o : values()) {
            if (o.name().equals(normalized) || normalized.startsWith(o.name() + "_")) {
                return o;
            }
        }
        throw new IllegalArgumentException("Unknown AgentOutcome: " + s);
    }
}
