package io.github.yysf1949.rag.agent.handoff;

/**
 * 转接渠道 — Agent 把"待处理项"丢到哪个渠道等人工。
 */
public enum HandoffChannel {
    /** 工单系统（异步，最常见） */
    WORK_ORDER,
    /** 在线客服（实时，对应 Agent 客服场景） */
    LIVE_CHAT,
    /** 电话外呼（紧急情况） */
    PHONE
}
