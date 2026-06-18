package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;

/**
 * 物流工具 — L1 只读查询。
 *
 * <h2>对齐文章 §"查询 ≠ 执行"</h2>
 * <p>物流查询是 Agent 客服最常见的开场工具：先查物流，再决定要不要催发货
 * （用 {@code create_reminder_ticket}）— 这是"低风险只读 → 高风险写"的标准分流。</p>
 */
@Component
public class LogisticsTool {

    @ToolSpec(
            name = "query_logistics",
            description = "查询订单物流轨迹（只读）。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public QueryResponse queryLogistics(QueryRequest req) {
        // mock 实现：返回固定的演示轨迹
        if (req.orderId().contains("DELAYED")) {
            return new QueryResponse(
                    req.orderId(),
                    "中转仓",
                    List.of(
                            new LogisticsEvent("2026-06-15T10:00:00Z", "杭州中转仓", "IN_TRANSIT"),
                            new LogisticsEvent("2026-06-16T08:00:00Z", "上海中转仓", "DELAYED")),
                    Instant.now().toString());
        }
        if (req.orderId().contains("UNKNOWN")) {
            return new QueryResponse(req.orderId(), "UNKNOWN", List.of(), null);
        }
        return new QueryResponse(
                req.orderId(),
                "北京-客户地址",
                List.of(
                        new LogisticsEvent("2026-06-17T14:00:00Z", "杭州发货", "SHIPPED"),
                        new LogisticsEvent("2026-06-17T20:00:00Z", "北京中转", "IN_TRANSIT"),
                        new LogisticsEvent("2026-06-18T08:00:00Z", "派送中", "DELIVERING")),
                "2026-06-18T15:00:00Z");
    }

    public record QueryRequest(String tenantId, String userId, String orderId) { }
    public record QueryResponse(String orderId, String currentLocation,
                                List<LogisticsEvent> events, String estimatedArrival) { }
    public record LogisticsEvent(String timestamp, String location, String status) { }
}
