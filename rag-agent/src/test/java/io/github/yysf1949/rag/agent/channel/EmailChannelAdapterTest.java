package io.github.yysf1949.rag.agent.channel;

import io.github.yysf1949.rag.agent.api.AgentChannel;
import io.github.yysf1949.rag.agent.api.AgentRequest;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmailChannelAdapter — 邮件渠道适配器")
class EmailChannelAdapterTest {

    private EmailChannelAdapter adapter;
    private AgentIdentity identity;

    @BeforeEach
    void setUp() {
        adapter = new EmailChannelAdapter();
        identity = new AgentIdentity("tenant-1", "user-1", "sess-1", Set.of("user"));
    }

    @Test
    @DisplayName("channel() 返回 EMAIL")
    void channelReturnsEmail() {
        assertEquals(AgentChannel.EMAIL, adapter.channel());
    }

    // ──────────────── parseInboundEmail ────────────────

    @Nested
    @DisplayName("parseInboundEmail")
    class ParseInboundEmailTests {

        @Test
        @DisplayName("正常邮件 → 解析为 ChannelMessage")
        void normalEmail() {
            ChannelMessage msg = adapter.parseInboundEmail(
                    "user@example.com", "support@company.com",
                    "[Ticket-ABC123] 问题咨询",
                    "你好，请帮我查一下订单状态。\n--\n张三",
                    null);

            assertEquals("user@example.com", msg.from());
            assertEquals("support@company.com", msg.to());
            assertEquals("[Ticket-ABC123] 问题咨询", msg.subject());
            // 签名应被剥离
            assertEquals("你好，请帮我查一下订单状态。", msg.body());
            // ticket ID 应作为 conversationId
            assertEquals("ABC123", msg.conversationId());
        }

        @Test
        @DisplayName("有回复引用（inReplyTo）→ conversationId 用 inReplyTo")
        void withInReplyTo() {
            ChannelMessage msg = adapter.parseInboundEmail(
                    "user@example.com", "support@company.com",
                    "Re: [Ticket-ABC123] 问题咨询",
                    "<p>补充说明：订单号 ORD-456</p>",
                    "<msg-789@mail.example.com>");

            // inReplyTo 优先于 ticket
            assertEquals("<msg-789@mail.example.com>", msg.conversationId());
            // HTML 应被转为纯文本
            assertFalse(msg.body().contains("<p>"));
            assertTrue(msg.body().contains("补充说明"));
        }

        @Test
        @DisplayName("HTML 邮件 → 自动转为纯文本")
        void htmlEmailConverted() {
            ChannelMessage msg = adapter.parseInboundEmail(
                    "user@example.com", "support@company.com",
                    "测试邮件",
                    "<html><body><h1>你好</h1><p>这是<b>HTML</b>邮件</p></body></html>",
                    null);

            assertFalse(msg.body().contains("<"));
            assertTrue(msg.body().contains("你好"));
            assertTrue(msg.body().contains("HTML"));
        }
    }

    // ──────────────── formatReply ────────────────

    @Nested
    @DisplayName("formatReply")
    class FormatReplyTests {

        @Test
        @DisplayName("生成正确格式的 EmailReply")
        void correctFormat() {
            ChannelMessage replyMsg = new ChannelMessage(
                    "support@company.com", "user@example.com",
                    "帮助反馈", "已收到您的问题，正在处理。", null);

            EmailReply reply = adapter.formatReply(replyMsg);

            assertNotNull(reply);
            assertTrue(reply.subject().startsWith("Re: "));
            assertTrue(reply.body().contains("已收到您的问题"));
        }

        @Test
        @DisplayName("原始邮件有 ticket ID → 回复主题保留 ticket")
        void preservesTicketId() {
            ChannelMessage replyMsg = new ChannelMessage(
                    "support@company.com", "user@example.com",
                    "[Ticket-ABC123] 问题咨询", "已处理完毕。", "ABC123");

            EmailReply reply = adapter.formatReply(replyMsg);

            // ticket 应保留
            assertTrue(reply.subject().contains("[Ticket-ABC123]"));
            assertTrue(reply.subject().startsWith("Re: "));
            assertEquals("已处理完毕。", reply.body());
        }

        @Test
        @DisplayName("原始邮件无 ticket ID → 回复自动生成新 ticket")
        void generatesNewTicket() {
            ChannelMessage replyMsg = new ChannelMessage(
                    "support@company.com", "user@example.com",
                    "普通邮件", "已收到。", null);

            EmailReply reply = adapter.formatReply(replyMsg);

            // 应自动添加 ticket
            assertTrue(reply.subject().contains("[Ticket-"));
            assertTrue(reply.subject().startsWith("Re: "));
            assertTrue(reply.subject().contains("普通邮件"));
        }

        @Test
        @DisplayName("formatReply 保留 conversationId 到 inReplyTo")
        void preservesInReplyTo() {
            ChannelMessage replyMsg = new ChannelMessage(
                    "support@company.com", "user@example.com",
                    "[Ticket-XYZ] 测试", "回复内容", "<orig-msg-id@mail.com>");

            EmailReply reply = adapter.formatReply(replyMsg);

            assertEquals("<orig-msg-id@mail.com>", reply.inReplyTo());
        }
    }
}
