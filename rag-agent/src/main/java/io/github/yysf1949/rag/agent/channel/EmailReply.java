package io.github.yysf1949.rag.agent.channel;

/**
 * 邮件回复的结构 — 由 {@link EmailChannelAdapter#formatReply} 生成，
 * 交给邮件发送网关（Spring Integration / JavaMail）发出。
 *
 * @param subject   回复邮件主题（自动携带 [Ticket-XXX] 前缀）
 * @param body      回复邮件正文（纯文本格式）
 * @param inReplyTo 原始邮件的 Message-ID（用于邮件线程串联，可为 null 表示新会话）
 */
public record EmailReply(
        String subject,
        String body,
        String inReplyTo
) { }
