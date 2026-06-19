package io.github.yysf1949.rag.agent.channel;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("EmailMessageParser — 纯字符串解析")
class EmailMessageParserTest {

    // ──────────────── extractTicketId ────────────────

    @Nested
    @DisplayName("extractTicketId")
    class ExtractTicketIdTests {

        @Test
        @DisplayName("有 [Ticket-XXX] 前缀 → 返回 XXX")
        void withTicketPrefix() {
            assertEquals("ABC123", EmailMessageParser.extractTicketId("[Ticket-ABC123] 我的问题"));
        }

        @Test
        @DisplayName("无前缀 → 返回 null")
        void withoutTicketPrefix() {
            assertNull(EmailMessageParser.extractTicketId("普通邮件主题"));
        }

        @Test
        @DisplayName("null 输入 → 返回 null")
        void nullSubject() {
            assertNull(EmailMessageParser.extractTicketId(null));
        }

        @Test
        @DisplayName("空字符串 → 返回 null")
        void emptySubject() {
            assertNull(EmailMessageParser.extractTicketId(""));
        }

        @Test
        @DisplayName("ticket 在主题中间 → 也能提取")
        void ticketInMiddle() {
            assertEquals("X-1", EmailMessageParser.extractTicketId("Re: [Ticket-X-1] 你的问题"));
        }
    }

    // ──────────────── stripSignature ────────────────

    @Nested
    @DisplayName("stripSignature")
    class StripSignatureTests {

        @Test
        @DisplayName("有签名（标准 -- 分隔符）→ 剥离签名")
        void withSignature() {
            String body = "你好，请帮我查一下订单状态。\n--\n张三\n客服部";
            String stripped = EmailMessageParser.stripSignature(body);
            assertEquals("你好，请帮我查一下订单状态。", stripped);
        }

        @Test
        @DisplayName("有签名（带空格 -- ）→ 剥离签名")
        void withSignatureTrailingSpace() {
            String body = "正文内容\n-- \n签名区域";
            String stripped = EmailMessageParser.stripSignature(body);
            assertEquals("正文内容", stripped);
        }

        @Test
        @DisplayName("无签名 → 原样返回")
        void withoutSignature() {
            String body = "纯文本，没有签名分隔符";
            assertEquals(body, EmailMessageParser.stripSignature(body));
        }

        @Test
        @DisplayName("null 输入 → 返回 null")
        void nullBody() {
            assertNull(EmailMessageParser.stripSignature(null));
        }

        @Test
        @DisplayName("空字符串 → 返回空字符串")
        void emptyBody() {
            assertEquals("", EmailMessageParser.stripSignature(""));
        }
    }

    // ──────────────── htmlToPlainText ────────────────

    @Nested
    @DisplayName("htmlToPlainText")
    class HtmlToPlainTextTests {

        @Test
        @DisplayName("简单 HTML 标签 → 移除标签")
        void removesTags() {
            String html = "<p>你好<b>世界</b></p>";
            String result = EmailMessageParser.htmlToPlainText(html);
            assertEquals("你好世界", result);
        }

        @Test
        @DisplayName("<br> 标签 → 转换为换行")
        void brToNewline() {
            String html = "第一行<br>第二行<br/>第三行";
            String result = EmailMessageParser.htmlToPlainText(html);
            assertTrue(result.contains("\n"));
            assertFalse(result.contains("<"));
        }

        @Test
        @DisplayName("HTML 实体 → 解码")
        void decodesEntities() {
            String html = "a &amp; b &lt; c &gt; d &quot;e&quot;";
            String result = EmailMessageParser.htmlToPlainText(html);
            assertEquals("a & b < c > d \"e\"", result);
        }

        @Test
        @DisplayName("null 输入 → 返回 null")
        void nullInput() {
            assertNull(EmailMessageParser.htmlToPlainText(null));
        }

        @Test
        @DisplayName("纯文本（无标签）→ 不变")
        void plainTextUnchanged() {
            String text = "纯文本无标签";
            assertEquals(text, EmailMessageParser.htmlToPlainText(text));
        }
    }

    // ──────────────── extractConversationId ────────────────

    @Nested
    @DisplayName("extractConversationId")
    class ExtractConversationIdTests {

        @Test
        @DisplayName("有 inReplyTo → 优先使用 inReplyTo")
        void withInReplyTo() {
            String id = EmailMessageParser.extractConversationId(
                    "<msg-123@server.com>", "[Ticket-ABC] test");
            assertEquals("<msg-123@server.com>", id);
        }

        @Test
        @DisplayName("无 inReplyTo，有 ticket → 从 subject 提取")
        void withoutInReplyToWithTicket() {
            String id = EmailMessageParser.extractConversationId(null, "[Ticket-ABC] test");
            assertEquals("ABC", id);
        }

        @Test
        @DisplayName("无 inReplyTo，无 ticket → 返回 null（新会话）")
        void withoutInReplyToWithoutTicket() {
            String id = EmailMessageParser.extractConversationId(null, "普通主题");
            assertNull(id);
        }

        @Test
        @DisplayName("inReplyTo 为空字符串 → 跳过，用 subject")
        void blankInReplyTo() {
            String id = EmailMessageParser.extractConversationId("  ", "[Ticket-X] test");
            assertEquals("X", id);
        }

        @Test
        @DisplayName("inReplyTo 会去除首尾空白")
        void inReplyToTrimmed() {
            String id = EmailMessageParser.extractConversationId("  <abc@def.com>  ", null);
            assertEquals("<abc@def.com>", id);
        }
    }
}
