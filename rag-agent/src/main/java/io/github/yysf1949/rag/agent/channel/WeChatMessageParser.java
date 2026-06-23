package io.github.yysf1949.rag.agent.channel;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信 XML 消息解析器 — 纯字符串解析，不依赖外部 XML 库。
 *
 * <p>微信公众号/企业微信推送的消息均为 XML 格式，本类负责：
 * <ul>
 *   <li>解析各类消息（text / image / voice / event）为统一 Map</li>
 *   <li>构建微信 XML 回复（text / news）</li>
 * </ul>
 *
 * <h2>设计约束</h2>
 * <p>不引入第三方 XML 库，用简单字符串解析，减少依赖。对于生产环境
 * 可后续替换为 javax.xml.parsers DOM 解析。</p>
 */
public final class WeChatMessageParser {

    private WeChatMessageParser() { }

    // ──────────────────── 解析 ────────────────────

    /**
     * 通用解析：从 XML 中提取所有标签内容，返回 tag → value 映射。
     * 支持 CDATA 包裹。
     */
    public static Map<String, String> parseXml(String xml) {
        Map<String, String> result = new HashMap<>();
        if (xml == null || xml.isBlank()) {
            return result;
        }
        // 匹配 <TagName><![CDATA[value]]></TagName> 或 <TagName>value</TagName>
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("<(\\w+)><!\\[CDATA\\[(.*?)]]></\\1>|<(\\w+)>([^<]*)</\\3>")
                .matcher(xml);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                result.put(matcher.group(1), matcher.group(2));
            } else if (matcher.group(3) != null) {
                result.put(matcher.group(3), matcher.group(4));
            }
        }
        return result;
    }

    /** 解析微信文本消息 */
    public static Map<String, String> parseTextMessage(String xml) {
        return parseXml(xml);
    }

    /** 解析微信图片消息 */
    public static Map<String, String> parseImageMessage(String xml) {
        return parseXml(xml);
    }

    /** 解析微信语音消息 */
    public static Map<String, String> parseVoiceMessage(String xml) {
        return parseXml(xml);
    }

    /** 解析微信事件消息（subscribe/unsubscribe/CLICK 等） */
    public static Map<String, String> parseEventMessage(String xml) {
        return parseXml(xml);
    }

    // ──────────────────── 构建回复 ────────────────────

    /**
     * 构建微信文本回复 XML。
     *
     * @param fromUser 发送方（公众号原始 ID，如 gh_xxx）
     * @param toUser   接收方（用户 openId）
     * @param content  回复文本内容
     * @return 微信 XML 回复字符串
     */
    public static String buildTextReply(String fromUser, String toUser, String content) {
        return "<xml>" +
                "<ToUserName><![CDATA[" + toUser + "]]></ToUserName>" +
                "<FromUserName><![CDATA[" + fromUser + "]]></FromUserName>" +
                "<CreateTime>" + (System.currentTimeMillis() / 1000) + "</CreateTime>" +
                "<MsgType><![CDATA[text]]></MsgType>" +
                "<Content><![CDATA[" + content + "]]></Content>" +
                "</xml>";
    }

    /**
     * 构建微信图文回复 XML。
     *
     * @param fromUser 发送方（公众号原始 ID）
     * @param toUser   接收方（用户 openId）
     * @param items    图文列表
     * @return 微信 XML 回复字符串
     */
    public static String buildNewsReply(String fromUser, String toUser, List<NewsItem> items) {
        StringBuilder sb = new StringBuilder();
        sb.append("<xml>");
        sb.append("<ToUserName><![CDATA[").append(toUser).append("]]></ToUserName>");
        sb.append("<FromUserName><![CDATA[").append(fromUser).append("]]></FromUserName>");
        sb.append("<CreateTime>").append(System.currentTimeMillis() / 1000).append("</CreateTime>");
        sb.append("<MsgType><![CDATA[news]]></MsgType>");
        sb.append("<ArticleCount>").append(items.size()).append("</ArticleCount>");
        sb.append("<Articles>");
        for (NewsItem item : items) {
            sb.append("<item>");
            sb.append("<Title><![CDATA[").append(item.title()).append("]]></Title>");
            sb.append("<Description><![CDATA[").append(item.description()).append("]]></Description>");
            sb.append("<PicUrl><![CDATA[").append(item.picUrl()).append("]]></PicUrl>");
            sb.append("<Url><![CDATA[").append(item.url()).append("]]></Url>");
            sb.append("</item>");
        }
        sb.append("</Articles>");
        sb.append("</xml>");
        return sb.toString();
    }

    /**
     * 图文消息项。
     */
    public record NewsItem(String title, String description, String picUrl, String url) { }
}
