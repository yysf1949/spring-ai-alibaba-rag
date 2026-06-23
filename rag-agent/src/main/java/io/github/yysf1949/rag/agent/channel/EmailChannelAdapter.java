package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.api.ChannelAdapter;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 邮件渠道适配器 — 把入站邮件（SMTP / IMAP webhook）转为统一 AgentRequest。
 *
 * <h2>邮件特有逻辑</h2>
 * <ul>
 *   <li>Subject 前缀 {@code [Ticket-XXX]} 作为会话 ID（conversationId），类比微信 sessionId</li>
 *   <li>签名自动剥离（识别 {@code -- } 标准签名分隔符）</li>
 *   <li>HTML 邮件纯文本提取（{@link EmailMessageParser#htmlToPlainText}）</li>
 *   <li>In-Reply-To header 作为回复引用，映射到 conversationId</li>
 * </ul>
 *
 * <h2>消息类型</h2>
 * <ul>
 *   <li>toolName = "email/inbound"</li>
 *   <li>payload = {from, to, subject, body, conversationId, messageId}</li>
 * </ul>
 *
 * @see EmailMessageParser 低级字符串解析工具
 * @see ChannelMessage     渠道消息中间模型
 * @see EmailReply         邮件回复结构
 */
@Component
public class EmailChannelAdapter implements ChannelAdapter {

    @Override
    public AgentChannel channel() {
        return AgentChannel.EMAIL;
    }

    @Override
    public AgentRequest parse(Object raw, AgentIdentity identity) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(
                    "Email channel expects Map body, got: " +
                            (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, String> mail = (Map<String, String>) raw;

        String from = mail.getOrDefault("from", "");
        String to = mail.getOrDefault("to", "");
        String subject = mail.getOrDefault("subject", "");
        String body = mail.getOrDefault("body", "");
        String inReplyTo = mail.getOrDefault("inReplyTo", null);
        String messageId = mail.getOrDefault("messageId", null);

        // 解析邮件
        ChannelMessage msg = parseInboundEmail(from, to, subject, body, inReplyTo);

        // 构建 payload（使用解析后的纯文本 body）
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("from", msg.from());
        payload.put("to", msg.to());
        payload.put("subject", msg.subject());
        payload.put("body", msg.body());
        if (msg.conversationId() != null) {
            payload.put("conversationId", msg.conversationId());
        }

        // 幂等键：用邮件 Message-ID（若有），否则不生成
        IdempotencyKey idem = null;
        if (messageId != null && !messageId.isBlank()) {
            idem = IdempotencyKey.of(
                    identity.tenantId(), identity.userId(),
                    identity.sessionId(), "email/inbound", messageId);
        }

        return new AgentRequest(identity, "email/inbound", payload, idem,
                AgentChannel.EMAIL, false);
    }

    /**
     * 解析入站邮件为渠道消息。
     *
     * <p>预处理链：HTML→纯文本 → 签名剥离 → ticket/inReplyTo 提取。</p>
     *
     * @param from      发件人地址
     * @param to        收件人地址
     * @param subject   邮件主题
     * @param body      邮件正文（可能含 HTML）
     * @param inReplyTo 原始邮件的 Message-ID（回复引用，可为 null）
     * @return 渠道消息
     */
    public ChannelMessage parseInboundEmail(String from, String to,
                                             String subject, String body,
                                             String inReplyTo) {
        // 1. HTML → 纯文本（安全幂等：已是纯文本时不变）
        String plainText = EmailMessageParser.htmlToPlainText(body);
        // 2. 剥离签名
        plainText = EmailMessageParser.stripSignature(plainText);
        // 3. 提取会话 ID
        String conversationId = EmailMessageParser.extractConversationId(inReplyTo, subject);

        return new ChannelMessage(from, to, subject, plainText, conversationId);
    }

    /**
     * 将渠道回复消息格式化为邮件回复。
     *
     * <p>若原始邮件有 ticket ID，回复主题保留该前缀，确保邮件线程串联。
     * 若无 ticket，生成新 ticket 并写入回复主题。</p>
     *
     * @param reply 渠道消息（subject 中应含原始邮件主题）
     * @return 邮件回复结构（交给邮件发送网关）
     */
    public EmailReply formatReply(ChannelMessage reply) {
        String ticketId = EmailMessageParser.extractTicketId(reply.subject());
        String replySubject;
        if (ticketId != null) {
            replySubject = "Re: " + reply.subject();
        } else {
            String newTicket = UUID.randomUUID().toString().substring(0, 8);
            replySubject = "Re: [Ticket-" + newTicket + "] " + reply.subject();
        }
        return new EmailReply(replySubject, reply.body(), reply.conversationId());
    }
}
