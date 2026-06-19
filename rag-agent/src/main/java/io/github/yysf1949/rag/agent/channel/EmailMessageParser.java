package io.github.yysf1949.rag.agent.channel;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 邮件消息解析器 — 纯字符串解析，0 外部依赖。
 *
 * <p>对齐 {@link WeChatMessageParser} 的设计：纯工具类，构造器私有，所有方法 static。
 * 负责从邮件原始字段（Subject / Body / In-Reply-To）提取会话 ID、剥离签名、
 * HTML→纯文本转换。</p>
 *
 * <h2>ticket 机制</h2>
 * <p>邮件渠道用 {@code [Ticket-XXX]} 作为 Subject 前缀标识会话，
 * 类比微信的 sessionId。新会话无此前缀，由系统生成 ticket 并在回复时写入 Subject。</p>
 */
public final class EmailMessageParser {

    private EmailMessageParser() { }

    /**
     * ticket ID 正则 — 匹配 Subject 中的 [Ticket-ABC123] 模式。
     * <p>ticket 只允许字母、数字、连字符、下划线，长度 1–64。</p>
     */
    private static final Pattern TICKET_PATTERN =
            Pattern.compile("\\[Ticket-([A-Za-z0-9_-]{1,64})]");

    /**
     * 从邮件主题中提取 ticket ID。
     *
     * @param subject 邮件主题（可为 null）
     * @return ticket ID 字符串，或 null（主题中不包含 [Ticket-XXX]）
     */
    public static String extractTicketId(String subject) {
        if (subject == null || subject.isBlank()) {
            return null;
        }
        Matcher m = TICKET_PATTERN.matcher(subject);
        return m.find() ? m.group(1) : null;
    }

    /**
     * 剥离邮件签名。
     *
     * <p>识别标准签名分隔符 {@code -- }（短横线横横线空格，独占一行），
     * 移除该行及之后所有内容。部分邮件客户端用 {@code --\n} 或 {@code --\r\n}，
     * 本方法统一处理。</p>
     *
     * @param body 邮件正文（可为 null）
     * @return 去除签名后的正文；无签名则原样返回
     */
    public static String stripSignature(String body) {
        if (body == null || body.isBlank()) {
            return body;
        }
        // 匹配行首 "-- " 或 "--" 后跟行尾（兼容 \r\n 和 \n）
        Matcher m = Pattern.compile("(?m)^--\\s*$").matcher(body);
        if (m.find()) {
            return body.substring(0, m.start()).stripTrailing();
        }
        return body;
    }

    /**
     * HTML 邮件转纯文本。
     *
     * <p>简单实现：移除所有 HTML 标签，解码常见 HTML 实体，压缩多余空白。
     * 生产环境可替换为 Jsoup 等完整解析器。</p>
     *
     * @param html HTML 字符串（可为 null）
     * @return 纯文本；输入为 null 时返回 null
     */
    public static String htmlToPlainText(String html) {
        if (html == null) {
            return null;
        }
        String text = html;
        // 先把 <br> / <p> / <div> 转为换行，保证段落分隔
        text = text.replaceAll("(?i)<br\\s*/?>", "\n");
        text = text.replaceAll("(?i)</p>", "\n\n");
        text = text.replaceAll("(?i)</div>", "\n");
        // 移除所有 HTML 标签
        text = text.replaceAll("<[^>]+>", "");
        // 解码常见 HTML 实体
        text = text.replace("&amp;", "&");
        text = text.replace("&lt;", "<");
        text = text.replace("&gt;", ">");
        text = text.replace("&quot;", "\"");
        text = text.replace("&#39;", "'");
        text = text.replace("&nbsp;", " ");
        // 压缩连续空行为单空行，去首尾空白
        text = text.replaceAll("\n{3,}", "\n\n").strip();
        return text;
    }

    /**
     * 提取邮件会话 ID。
     *
     * <p>优先使用 {@code inReplyTo}（邮件 Message-ID 回复引用），
     * 其次从 Subject 提取 ticket ID，两者均无则返回 null（新会话）。</p>
     *
     * @param inReplyTo 原始邮件 In-Reply-To header（可为 null）
     * @param subject   邮件主题（可为 null）
     * @return 会话 ID；null 表示新会话
     */
    public static String extractConversationId(String inReplyTo, String subject) {
        // 优先使用 In-Reply-To（邮件客户端的引用标识）
        if (inReplyTo != null && !inReplyTo.isBlank()) {
            return inReplyTo.trim();
        }
        // 其次从 Subject 提取 ticket ID
        return extractTicketId(subject);
    }
}
