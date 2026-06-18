package io.github.yysf1949.rag.agent.api;

/**
 * Agent 请求来源渠道 — 对齐「路条编程」AI 客服文章 5 层架构的"渠道接入层"。
 *
 * <h2>多渠道支持</h2>
 * <p>当前实现 HTTP / 微信 / 邮件 / APP 四种入口，统一封装为 {@link AgentRequest}。
 * 业务侧不感知渠道差异，由 {@code ChannelAdapter} 适配。</p>
 */
public enum AgentChannel {
    /** Web 后台 / API 调用方 — 主要是 B 端或 H5 */
    HTTP(true),
    /** 微信公众号 / 企业微信 — 微信生态客服 */
    WECHAT(true),
    /** 邮件工单入口 — 异步处理 */
    EMAIL(true),
    /** 移动 App 内嵌 SDK */
    APP(true);

    private final boolean humanFacing;

    AgentChannel(boolean humanFacing) {
        this.humanFacing = humanFacing;
    }

    /** 是否由真人触发（true）vs 系统触发（false — 留给后续 Phase） */
    public boolean isHumanFacing() {
        return humanFacing;
    }

    /**
     * 从 HTTP header 解析渠道 — 兼容不同命名风格。
     *
     * @param header X-Channel 值（可为 null）
     * @return 解析结果，null 或未知值默认 HTTP
     */
    public static AgentChannel fromHeader(String header) {
        if (header == null || header.isBlank()) {
            return HTTP;
        }
        String normalized = header.toUpperCase().replace('-', '_').split("[-_]")[0];
        for (AgentChannel c : values()) {
            if (c.name().equals(normalized)) {
                return c;
            }
        }
        return HTTP; // 未知值兜底
    }
}
