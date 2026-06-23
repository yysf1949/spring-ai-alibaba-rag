package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.*;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class WeChatChannelAdapterTest {

    private WeChatChannelAdapter adapter;
    private AgentIdentity identity;

    @BeforeEach
    void setUp() {
        adapter = new WeChatChannelAdapter();
        identity = new AgentIdentity("wx-appid-001", "oUser123", "sess-001", Set.of("user"));
    }

    @Test
    @DisplayName("channel() 返回 WECHAT")
    void channelReturnsWechat() {
        assertEquals(AgentChannel.WECHAT, adapter.channel());
    }

    @Test
    @DisplayName("文本消息 → AgentRequest")
    void parseTextMessage() {
        Map<String, String> raw = Map.of(
                "ToUserName",   "gh_test",
                "FromUserName", "oUser123",
                "CreateTime",   "1348831860",
                "MsgType",      "text",
                "Content",      "你好",
                "MsgId",        "1234567890"
        );

        AgentRequest req = adapter.parse(raw, identity);

        assertEquals("wechat/text", req.toolName());
        assertEquals(AgentChannel.WECHAT, req.channel());
        assertNotNull(req.idempotencyKey());

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) req.requestPayload();
        assertEquals("你好", payload.get("content"));
        assertEquals("oUser123", payload.get("fromUser"));
        assertEquals("gh_test",  payload.get("toUser"));
    }

    @Test
    @DisplayName("图片消息 → AgentRequest")
    void parseImageMessage() {
        Map<String, String> raw = Map.of(
                "ToUserName",   "gh_test",
                "FromUserName", "oUser123",
                "MsgType",      "image",
                "MediaId",      "media_id_abc",
                "PicUrl",       "http://img.weixin.qq.com/pic",
                "MsgId",        "2234567890"
        );

        AgentRequest req = adapter.parse(raw, identity);

        assertEquals("wechat/image", req.toolName());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) req.requestPayload();
        assertEquals("media_id_abc", payload.get("mediaId"));
        assertEquals("http://img.weixin.qq.com/pic", payload.get("picUrl"));
    }

    @Test
    @DisplayName("语音消息 → AgentRequest")
    void parseVoiceMessage() {
        Map<String, String> raw = Map.of(
                "ToUserName",   "gh_test",
                "FromUserName", "oUser123",
                "MsgType",      "voice",
                "MediaId",      "voice_media_id",
                "Format",       "amr",
                "Recognition",  "你好世界",
                "MsgId",        "3234567890"
        );

        AgentRequest req = adapter.parse(raw, identity);

        assertEquals("wechat/voice", req.toolName());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) req.requestPayload();
        assertEquals("voice_media_id", payload.get("mediaId"));
        assertEquals("你好世界", payload.get("recognition"));
        assertEquals("amr", payload.get("format"));
    }

    @Test
    @DisplayName("事件消息 (subscribe) → AgentRequest")
    void parseSubscribeEvent() {
        Map<String, String> raw = Map.of(
                "ToUserName",   "gh_test",
                "FromUserName", "oUser123",
                "CreateTime",   "1348831860",
                "MsgType",      "event",
                "Event",        "subscribe",
                "EventKey",     ""
        );

        AgentRequest req = adapter.parse(raw, identity);

        assertEquals("wechat/event", req.toolName());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) req.requestPayload();
        assertEquals("subscribe", payload.get("event"));
        assertEquals("", payload.get("eventKey"));
        assertNull(req.idempotencyKey()); // 无 MsgId
    }

    @Test
    @DisplayName("事件消息 (CLICK) → AgentRequest")
    void parseClickEvent() {
        Map<String, String> raw = Map.of(
                "ToUserName",   "gh_test",
                "FromUserName", "oUser123",
                "MsgType",      "event",
                "Event",        "CLICK",
                "EventKey",     "V1001_TODAY_MUSIC"
        );

        AgentRequest req = adapter.parse(raw, identity);

        assertEquals("wechat/event", req.toolName());
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) req.requestPayload();
        assertEquals("CLICK", payload.get("event"));
        assertEquals("V1001_TODAY_MUSIC", payload.get("eventKey"));
    }

    @Test
    @DisplayName("AgentResponse → 微信 XML 回复")
    void toWeChatReply() {
        AgentResponse response = new AgentResponse(
                "wechat/text",
                AgentOutcome.SUCCESS,
                null,
                "查询完成，您的订单已发货",
                120L,
                null
        );

        String xml = adapter.toWeChatReply(response, "gh_test", "oUser123");

        assertTrue(xml.contains("<ToUserName><![CDATA[oUser123]]></ToUserName>"));
        assertTrue(xml.contains("<FromUserName><![CDATA[gh_test]]></FromUserName>"));
        assertTrue(xml.contains("<MsgType><![CDATA[text]]></MsgType>"));
        assertTrue(xml.contains("查询完成，您的订单已发货"));
    }

    @Test
    @DisplayName("AgentResponse 为 null 时返回默认提示")
    void toWeChatReplyNullResponse() {
        String xml = adapter.toWeChatReply(null, "gh_test", "oUser123");
        assertTrue(xml.contains("系统繁忙"));
    }

    @Test
    @DisplayName("原始数据类型不对时抛异常")
    void parseInvalidType() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.parse("not a map", identity));
    }

    @Test
    @DisplayName("身份映射：identity 字段正确传递")
    void identityMapping() {
        Map<String, String> raw = Map.of(
                "ToUserName",   "gh_test",
                "FromUserName", "oUser123",
                "MsgType",      "text",
                "Content",      "hi",
                "MsgId",        "999"
        );

        AgentRequest req = adapter.parse(raw, identity);

        assertEquals("wx-appid-001", req.identity().tenantId());
        assertEquals("oUser123",     req.identity().userId());
        assertEquals("sess-001",     req.identity().sessionId());
    }
}
