package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.*;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 微信渠道适配器 — 把微信公众号/企业微信推送的 XML 消息转为统一 AgentRequest。
 *
 * <h2>消息类型映射</h2>
 * <ul>
 *   <li>text   → toolName="wechat/text",  payload={content, msgId}</li>
 *   <li>image  → toolName="wechat/image", payload={mediaId, msgId}</li>
 *   <li>voice  → toolName="wechat/voice", payload={mediaId, recognition, msgId}</li>
 *   <li>event  → toolName="wechat/event", payload={event, eventKey}</li>
 * </ul>
 *
 * <h2>身份映射</h2>
 * <ul>
 *   <li>openId → userId</li>
 *   <li>微信 appId（AppID）→ tenantId</li>
 * </ul>
 *
 * <h2>回复格式转换</h2>
 * <p>AgentResponse → 微信 XML（text / image / news），由 {@link #toWeChatReply} 完成。</p>
 */
@Component
public class WeChatChannelAdapter implements ChannelAdapter {

    @Override
    public AgentChannel channel() {
        return AgentChannel.WECHAT;
    }

    @Override
    public AgentRequest parse(Object raw, AgentIdentity identity) {
        if (!(raw instanceof Map)) {
            throw new IllegalArgumentException(
                    "WeChat channel expects Map (parsed XML), got: " +
                            (raw == null ? "null" : raw.getClass().getSimpleName()));
        }
        @SuppressWarnings("unchecked")
        Map<String, String> msg = (Map<String, String>) raw;

        String msgType = msg.getOrDefault("MsgType", "text");
        String fromUser = msg.getOrDefault("FromUserName", "");
        String toUser = msg.getOrDefault("ToUserName", "");
        String msgId = msg.getOrDefault("MsgId", "");

        // 构建 payload
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("fromUser", fromUser);
        payload.put("toUser", toUser);
        payload.put("msgId", msgId);

        String toolName;
        switch (msgType.toLowerCase()) {
            case "text" -> {
                toolName = "wechat/text";
                payload.put("content", msg.getOrDefault("Content", ""));
            }
            case "image" -> {
                toolName = "wechat/image";
                payload.put("mediaId", msg.getOrDefault("MediaId", ""));
                payload.put("picUrl", msg.getOrDefault("PicUrl", ""));
            }
            case "voice" -> {
                toolName = "wechat/voice";
                payload.put("mediaId", msg.getOrDefault("MediaId", ""));
                payload.put("recognition", msg.getOrDefault("Recognition", ""));
                payload.put("format", msg.getOrDefault("Format", ""));
            }
            case "event" -> {
                toolName = "wechat/event";
                payload.put("event", msg.getOrDefault("Event", ""));
                payload.put("eventKey", msg.getOrDefault("EventKey", ""));
            }
            default -> {
                toolName = "wechat/" + msgType;
                payload.putAll(msg);
            }
        }

        IdempotencyKey idem = null;
        if (msgId != null && !msgId.isBlank()) {
            idem = IdempotencyKey.of(
                    identity.tenantId(), identity.userId(),
                    identity.sessionId(), toolName, msgId);
        }

        return new AgentRequest(identity, toolName, payload, idem,
                AgentChannel.WECHAT, false);
    }

    /**
     * 把 AgentResponse 转为微信 XML 回复。
     *
     * @param response   Agent 响应
     * @param fromUser   公众号原始 ID（gh_xxx）
     * @param toUser     用户 openId
     * @return 微信 XML 回复字符串
     */
    public String toWeChatReply(AgentResponse response, String fromUser, String toUser) {
        if (response == null) {
            return WeChatMessageParser.buildTextReply(fromUser, toUser,
                    "系统繁忙，请稍后重试");
        }

        String message = response.message();
        if (message == null || message.isBlank()) {
            message = "处理完成，状态: " + response.outcome();
        }

        // 简单文本回复（生产环境可根据 toolResponse 类型扩展 image/news）
        return WeChatMessageParser.buildTextReply(fromUser, toUser, message);
    }
}
