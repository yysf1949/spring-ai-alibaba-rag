package io.github.yysf1949.rag.agent.exception;

/**
 * L3 工具金额超限 — 触发转人工（HANDOFF_REQUIRED）。
 *
 * <p>对齐「路条编程」文章 §"评估指标要变" — L3 改业务态的工具要按金额做二次确认。
 * Agent 不能"自动退款 5 万"，超过 {@code maxAmountCents} 必须转人工审批。</p>
 */
public class AmountLimitExceededException extends RuntimeException {

    private final String toolName;
    private final long requestedCents;
    private final long limitCents;

    public AmountLimitExceededException(String toolName, long requestedCents, long limitCents) {
        super(String.format("Tool [%s] amount %d cents exceeds L3 limit %d cents — handoff required",
                toolName, requestedCents, limitCents));
        this.toolName = toolName;
        this.requestedCents = requestedCents;
        this.limitCents = limitCents;
    }

    public String toolName() { return toolName; }
    public long requestedCents() { return requestedCents; }
    public long limitCents() { return limitCents; }
}
