package io.github.yysf1949.rag.agent.channel;

/**
 * 渠道消息的统一模型 — 所有渠道（邮件、微信、HTTP）解析后产生的中间结构。
 *
 * <p>与 {@link io.github.yysf1949.rag.agent.api.AgentRequest} 的区别：
 * AgentRequest 是面向编排层的治理对象（含幂等键、dryRun 等），
 * ChannelMessage 是渠道层内部的消息模型，仅关心消息本身的字段。</p>
 *
 * @param from           发送者标识（邮件地址 / openId / userId）
 * @param to             接收者标识（邮箱地址 / 公众号 ID / 路由目标）
 * @param subject        主题 / 标题（邮件 Subject / 微信 Content 摘要）
 * @param body           正文内容（已做渠道预处理，如签名剥离、HTML→纯文本）
 * @param conversationId 会话 ID（邮件 ticket 前缀 / 微信 sessionId / null 为新会话）
 */
public record ChannelMessage(
        String from,
        String to,
        String subject,
        String body,
        String conversationId
) { }
