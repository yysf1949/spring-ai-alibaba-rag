package io.github.yysf1949.rag.agent.exception;

import java.util.List;

/**
 * 业务规则命中"必须人工"分支 — Agent 工具内部抛出,
 * 由编排层（{@code DefaultAgentLoop}）捕获并自动转人工。
 *
 * <h2>对齐「路条编程」文章 §"人工确认不是失败"</h2>
 * <p>文章原话："Agent 在转人工之前，已完成用户身份确认、订单信息查询、
 * 问题分类、规则匹配和风险说明" — 本异常携带 {@code matchedRules} 和 {@code riskNote}，
 * 让 {@link io.github.yysf1949.rag.agent.handoff.HandoffContext} 包含完整的"前置工作"
 * 证据，人工客服不需要重新问用户。</p>
 *
 * <h2>与 {@link AmountLimitExceededException} 的区别</h2>
 * <ul>
 *   <li>{@link AmountLimitExceededException} — 单一规则（金额超限）</li>
 *   <li>{@link HandoffRequiredException} — 多规则 + 业务复合（组合优惠 / 退款期 / 渠道不允许）</li>
 * </ul>
 *
 * <h2>HTTP 状态码映射</h2>
 * <p>由 {@code AgentExceptionHandler} 映射为 422 Unprocessable Entity（业务规则未通过，
 * 客户端应引导用户转人工而不是重试）。</p>
 */
public class HandoffRequiredException extends RuntimeException {

    private final String toolName;
    private final String reason;
    private final List<String> matchedRules;
    private final String riskNote;

    public HandoffRequiredException(String toolName, String reason,
                                     List<String> matchedRules, String riskNote) {
        super(String.format("Tool [%s] requires human handoff: %s (matched: %s)",
                toolName, reason, matchedRules));
        this.toolName = toolName;
        this.reason = reason;
        this.matchedRules = matchedRules == null ? List.of() : List.copyOf(matchedRules);
        this.riskNote = riskNote;
    }

    public String toolName() { return toolName; }
    public String reason() { return reason; }
    public List<String> matchedRules() { return matchedRules; }
    public String riskNote() { return riskNote; }
}