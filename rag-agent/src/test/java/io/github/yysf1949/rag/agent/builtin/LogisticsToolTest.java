package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LogisticsToolTest {

    private final LogisticsTool tool = new LogisticsTool();

    @Test
    void queryLogisticsHappyPath() {
        var resp = tool.queryLogistics(new LogisticsTool.QueryRequest(
                "tenant-1", "user-1", "ORD-1"));
        assertThat(resp.orderId()).isEqualTo("ORD-1");
        assertThat(resp.currentLocation()).isNotBlank();
        assertThat(resp.events()).isNotEmpty();
    }

    @Test
    void queryLogisticsUnknownOrder() {
        // mock 实现对未知订单返回空事件列表
        var resp = tool.queryLogistics(new LogisticsTool.QueryRequest(
                "tenant-1", "user-1", "ORD-UNKNOWN"));
        assertThat(resp.events()).isEmpty();
        assertThat(resp.currentLocation()).isEqualTo("UNKNOWN");
    }

    @Test
    void createReminderLogisticsUsesOrderId() {
        // 演示: query + create_reminder_ticket 串联 (实际由编排层组合)
        var query = tool.queryLogistics(new LogisticsTool.QueryRequest(
                "tenant-1", "user-1", "ORD-DELAYED"));
        assertThat(query.events()).extracting(LogisticsTool.LogisticsEvent::status)
                .contains("DELAYED");
    }
}
