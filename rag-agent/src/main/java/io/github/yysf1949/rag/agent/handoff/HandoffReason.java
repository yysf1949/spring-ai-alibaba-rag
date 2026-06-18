package io.github.yysf1949.rag.agent.handoff;

/**
 * 转人工原因 — 对齐「路条编程」文章 §"人工确认不是失败"。
 *
 * <p>每种原因对应不同的"Agent 已完成的前置工作" — 人工客服接手后
 * 不用重新问用户。</p>
 */
public enum HandoffReason {
    /** L3 金额超限 — 工具已查订单/规则/已生成草稿 */
    AMOUNT_LIMIT_EXCEEDED,
    /** L4 工具但用户非 admin — 工具已查所有信息，需要 admin 审批 */
    INSUFFICIENT_PRIVILEGE,
    /** 模型多次重试失败 — 工具尝试过所有相关查询 */
    RETRY_EXHAUSTED,
    /** 业务规则命中"必须人工"分支 — 工具已做规则匹配 */
    BUSINESS_RULE_MANDATES_HUMAN,
    /** 用户主动要求人工 */
    USER_REQUESTED
}
