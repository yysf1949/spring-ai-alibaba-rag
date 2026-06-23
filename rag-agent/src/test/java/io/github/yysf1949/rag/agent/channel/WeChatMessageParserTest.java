package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.channel.WeChatMessageParser.NewsItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class WeChatMessageParserTest {

    // ──────────────────── 解析测试 ────────────────────

    @Test
    @DisplayName("解析文本消息 XML")
    void parseTextMessage() {
        String xml = """
                <xml>
                <ToUserName><![CDATA[gh_test]]></ToUserName>
                <FromUserName><![CDATA[oUser123]]></FromUserName>
                <CreateTime>1348831860</CreateTime>
                <MsgType><![CDATA[text]]></MsgType>
                <Content><![CDATA[你好]]></Content>
                <MsgId>1234567890123456</MsgId>
                </xml>
                """;

        Map<String, String> msg = WeChatMessageParser.parseTextMessage(xml);

        assertEquals("gh_test",    msg.get("ToUserName"));
        assertEquals("oUser123",   msg.get("FromUserName"));
        assertEquals("1348831860", msg.get("CreateTime"));
        assertEquals("text",       msg.get("MsgType"));
        assertEquals("你好",        msg.get("Content"));
        assertEquals("1234567890123456", msg.get("MsgId"));
    }

    @Test
    @DisplayName("解析图片消息 XML")
    void parseImageMessage() {
        String xml = """
                <xml>
                <ToUserName><![CDATA[gh_test]]></ToUserName>
                <FromUserName><![CDATA[oUser123]]></FromUserName>
                <CreateTime>1348831860</CreateTime>
                <MsgType><![CDATA[image]]></MsgType>
                <PicUrl><![CDATA[http://img.weixin.qq.com/pic]]></PicUrl>
                <MediaId><![CDATA[media_id_abc]]></MediaId>
                <MsgId>2234567890123456</MsgId>
                </xml>
                """;

        Map<String, String> msg = WeChatMessageParser.parseImageMessage(xml);

        assertEquals("image",                     msg.get("MsgType"));
        assertEquals("http://img.weixin.qq.com/pic", msg.get("PicUrl"));
        assertEquals("media_id_abc",              msg.get("MediaId"));
        assertEquals("2234567890123456",          msg.get("MsgId"));
    }

    @Test
    @DisplayName("解析语音消息 XML")
    void parseVoiceMessage() {
        String xml = """
                <xml>
                <ToUserName><![CDATA[gh_test]]></ToUserName>
                <FromUserName><![CDATA[oUser123]]></FromUserName>
                <CreateTime>1348831860</CreateTime>
                <MsgType><![CDATA[voice]]></MsgType>
                <MediaId><![CDATA[voice_media_id]]></MediaId>
                <Format><![CDATA[amr]]></Format>
                <Recognition><![CDATA[你好世界]]></Recognition>
                <MsgId>3234567890123456</MsgId>
                </xml>
                """;

        Map<String, String> msg = WeChatMessageParser.parseVoiceMessage(xml);

        assertEquals("voice",          msg.get("MsgType"));
        assertEquals("voice_media_id", msg.get("MediaId"));
        assertEquals("amr",            msg.get("Format"));
        assertEquals("你好世界",         msg.get("Recognition"));
    }

    @Test
    @DisplayName("解析事件消息 (subscribe) XML")
    void parseSubscribeEvent() {
        String xml = """
                <xml>
                <ToUserName><![CDATA[gh_test]]></ToUserName>
                <FromUserName><![CDATA[oUser123]]></FromUserName>
                <CreateTime>1348831860</CreateTime>
                <MsgType><![CDATA[event]]></MsgType>
                <Event><![CDATA[subscribe]]></Event>
                <EventKey><![CDATA[]]></EventKey>
                </xml>
                """;

        Map<String, String> msg = WeChatMessageParser.parseEventMessage(xml);

        assertEquals("event",     msg.get("MsgType"));
        assertEquals("subscribe", msg.get("Event"));
        assertEquals("",          msg.get("EventKey"));
    }

    @Test
    @DisplayName("解析事件消息 (CLICK) XML")
    void parseClickEvent() {
        String xml = """
                <xml>
                <ToUserName><![CDATA[gh_test]]></ToUserName>
                <FromUserName><![CDATA[oUser123]]></FromUserName>
                <CreateTime>1348831860</CreateTime>
                <MsgType><![CDATA[event]]></MsgType>
                <Event><![CDATA[CLICK]]></Event>
                <EventKey><![CDATA[V1001_TODAY_MUSIC]]></EventKey>
                </xml>
                """;

        Map<String, String> msg = WeChatMessageParser.parseEventMessage(xml);

        assertEquals("CLICK",              msg.get("Event"));
        assertEquals("V1001_TODAY_MUSIC",  msg.get("EventKey"));
    }

    @Test
    @DisplayName("解析 null/空 XML 返回空 Map")
    void parseNullAndEmpty() {
        assertTrue(WeChatMessageParser.parseXml(null).isEmpty());
        assertTrue(WeChatMessageParser.parseXml("").isEmpty());
        assertTrue(WeChatMessageParser.parseXml("   ").isEmpty());
    }

    @Test
    @DisplayName("无 CDATA 的纯文本标签也能解析")
    void parsePlainTextTag() {
        String xml = "<xml><MsgType>text</MsgType><Content>hello</Content></xml>";
        Map<String, String> msg = WeChatMessageParser.parseXml(xml);

        assertEquals("text",  msg.get("MsgType"));
        assertEquals("hello", msg.get("Content"));
    }

    // ──────────────────── 构建回复测试 ────────────────────

    @Test
    @DisplayName("构建文本回复 XML")
    void buildTextReply() {
        String xml = WeChatMessageParser.buildTextReply("gh_test", "oUser123", "你好！");

        assertTrue(xml.startsWith("<xml>"));
        assertTrue(xml.contains("<ToUserName><![CDATA[oUser123]]></ToUserName>"));
        assertTrue(xml.contains("<FromUserName><![CDATA[gh_test]]></FromUserName>"));
        assertTrue(xml.contains("<MsgType><![CDATA[text]]></MsgType>"));
        assertTrue(xml.contains("<Content><![CDATA[你好！]]></Content>"));
        assertTrue(xml.contains("<CreateTime>"));
        assertTrue(xml.endsWith("</xml>"));
    }

    @Test
    @DisplayName("构建图文回复 XML")
    void buildNewsReply() {
        List<NewsItem> items = List.of(
                new NewsItem("标题1", "描述1", "http://img.com/1.jpg", "http://link.com/1"),
                new NewsItem("标题2", "描述2", "http://img.com/2.jpg", "http://link.com/2")
        );

        String xml = WeChatMessageParser.buildNewsReply("gh_test", "oUser123", items);

        assertTrue(xml.contains("<MsgType><![CDATA[news]]></MsgType>"));
        assertTrue(xml.contains("<ArticleCount>2</ArticleCount>"));
        assertTrue(xml.contains("<Title><![CDATA[标题1]]></Title>"));
        assertTrue(xml.contains("<Title><![CDATA[标题2]]></Title>"));
        assertTrue(xml.contains("<Description><![CDATA[描述1]]></Description>"));
        assertTrue(xml.contains("<PicUrl><![CDATA[http://img.com/1.jpg]]></PicUrl>"));
        assertTrue(xml.contains("<Url><![CDATA[http://link.com/2]]></Url>"));
    }

    @Test
    @DisplayName("构建空图文列表回复")
    void buildNewsReplyEmptyItems() {
        String xml = WeChatMessageParser.buildNewsReply("gh_test", "oUser123", List.of());

        assertTrue(xml.contains("<ArticleCount>0</ArticleCount>"));
        assertTrue(xml.contains("<Articles></Articles>") || xml.contains("<Articles/>"));
    }

    @Test
    @DisplayName("特殊字符在回复中被正确 CDATA 包裹")
    void buildTextReplyWithSpecialChars() {
        String xml = WeChatMessageParser.buildTextReply("gh_test", "oUser123",
                "包含<特殊>&字符");

        assertTrue(xml.contains("包含<特殊>&字符"));
        // CDATA 内的特殊字符不需要转义，验证结构正确
        assertTrue(xml.contains("<Content><![CDATA[包含<特殊>&字符]]></Content>"));
    }
}
