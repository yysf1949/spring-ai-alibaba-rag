package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConversationSummaryToolTest {

    private ConversationSummaryTool tool;

    @BeforeEach
    void setUp() {
        tool = new ConversationSummaryTool();
    }

    @Test
    void normalMessageListGeneratesSummary() {
        var resp = tool.generateSummary(new ConversationSummaryTool.SummaryRequest(
                List.of("你好", "我想退款", "订单号是 ORD-1", "好的已处理")));
        assertThat(resp.summary()).contains("4 条消息");
        assertThat(resp.messageCount()).isEqualTo(4);
    }

    @Test
    void emptyMessageListReturnsEmptySummary() {
        var resp = tool.generateSummary(new ConversationSummaryTool.SummaryRequest(List.of()));
        assertThat(resp.summary()).isEqualTo("无消息记录");
        assertThat(resp.keyIssues()).isEmpty();
        assertThat(resp.messageCount()).isEqualTo(0);
    }

    @Test
    void nullMessageListReturnsEmptySummary() {
        var resp = tool.generateSummary(new ConversationSummaryTool.SummaryRequest(null));
        assertThat(resp.summary()).isEqualTo("无消息记录");
        assertThat(resp.messageCount()).isEqualTo(0);
    }

    @Test
    void keyIssueExtractionWithQuestionMark() {
        var resp = tool.generateSummary(new ConversationSummaryTool.SummaryRequest(
                List.of("你好", "我的退款什么时候到账?", "已处理")));
        assertThat(resp.keyIssues()).hasSize(1);
        assertThat(resp.keyIssues().get(0)).contains("退款");
    }

    @Test
    void keyIssueExtractionWithKeywords() {
        var resp = tool.generateSummary(new ConversationSummaryTool.SummaryRequest(
                List.of("你好", "物流延迟三天了", "我要投诉", "正常消息")));
        assertThat(resp.keyIssues()).hasSize(2);
        assertThat(resp.keyIssues()).anyMatch(s -> s.contains("物流"));
        assertThat(resp.keyIssues()).anyMatch(s -> s.contains("投诉"));
    }

    @Test
    void noKeyIssuesWhenAllNormal() {
        var resp = tool.generateSummary(new ConversationSummaryTool.SummaryRequest(
                List.of("你好", "谢谢", "再见")));
        assertThat(resp.keyIssues()).isEmpty();
        assertThat(resp.summary()).contains("无明显关键问题");
    }
}
