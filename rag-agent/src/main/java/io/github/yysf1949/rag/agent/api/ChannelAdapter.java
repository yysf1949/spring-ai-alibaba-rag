package io.github.yysf1949.rag.agent.api;

import io.github.yysf1949.rag.agent.governance.AgentIdentity;

/**
 * 渠道适配器 — 把不同入口的请求 (HTTP/微信/邮件/APP) 统一封装为 {@link AgentRequest}。
 *
 * <h2>对齐「路条编程」文章 5 层架构 §1 渠道接入层</h2>
 * <p>不同渠道有不同的请求格式 (HTTP JSON / 微信 XML / 邮件 MIME / APP Protobuf),
 * ChannelAdapter 负责把外部格式转成统一的 AgentRequest, 让业务侧只看到
 * AgentRequest, 不感知渠道差异。</p>
 *
 * <h2>Phase 10 范围</h2>
 * <ul>
 *   <li><b>已实现</b>: {@code ChannelAdapter} interface, {@code HttpChannelAdapter} (现有 HTTP 入口封装)</li>
 *   <li><b>Phase 11 计划</b>: WechatChannelAdapter, EmailChannelAdapter, AppChannelAdapter</li>
 * </ul>
 */
public interface ChannelAdapter {

    /** 该 adapter 处理的渠道 */
    AgentChannel channel();

    /**
     * 把外部请求解析成 AgentRequest。
     *
     * @param raw      原始请求体 (HTTP Map / 微信 XML 解析结果 / 邮件 MIME)
     * @param identity 调用者身份 (从 HTTP header / 微信 openId / 邮件 From 提取)
     * @return 统一 AgentRequest
     */
    AgentRequest parse(Object raw, AgentIdentity identity);
}
