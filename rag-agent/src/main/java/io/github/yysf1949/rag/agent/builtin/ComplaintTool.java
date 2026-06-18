package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.ComplaintRepositoryPort;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

/**
 * 投诉工单工具 — L3 业务态写操作。
 *
 * <h2>与 TicketTool 的区别</h2>
 * <p>{@link TicketTool} 创建的是"提醒工单"（L2，不涉及核心业务）。
 * 投诉工单是更严肃的业务事件：有分类、有优先级、有处理时限、
 * 需要升级到管理层审批。对应 L3 风险级别。</p>
 *
 * <h2>对齐文章"创建工单"</h2>
 * <p>文章将"创建工单"列为 Agent 可执行的关键写操作之一。
 * 投诉是工单的高级形态 — 代表用户对服务不满意，需要更严肃对待。</p>
 *
 * <h2>分类与优先级</h2>
 * <ul>
 *   <li>category: SERVICE（服务态度）/ QUALITY（商品质量）/ LOGISTICS（物流问题）/ OTHER</li>
 *   <li>priority: P0（紧急）/ P1（高）/ P2（中）/ P3（低）— 默认 P2，P0 需转人工</li>
 * </ul>
 */
@Component
public class ComplaintTool {

    private final ComplaintRepositoryPort repo;
    private final IdempotencyStore idempotencyStore;

    public ComplaintTool(ComplaintRepositoryPort repo, IdempotencyStore idempotencyStore) {
        this.repo = repo;
        this.idempotencyStore = idempotencyStore;
    }

    @ToolSpec(
            name = "create_complaint",
            description = "创建投诉工单（支持分类：服务态度/商品质量/物流问题/其他）。"
                    + "优先级默认 P2（中），P0 紧急投诉需转人工。"
                    + "适用于：用户明确表达不满、要求投诉、需要升级处理。",
            riskLevel = RiskLevel.L3_BUSINESS_STATE,
            idempotent = false,
            requiresIdempotencyKey = true,
            maxAmountCents = -1L
    )
    public ComplaintResponse createComplaint(ComplaintRequest req, IdempotencyKey idempotencyKey) {
        // 幂等检查
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            String existingId = (String) put.value();
            if (existingId != null) {
                return new ComplaintResponse(existingId, "CREATED", req.priority(), "投诉已受理（幂等回放）");
            }
        }

        // P0 紧急投诉 — 强制转人工
        if ("P0".equals(req.priority())) {
            return new ComplaintResponse(null, "ESCALATED", "P0",
                    "紧急投诉已记录，正在为您转接专属客服经理");
        }

        String complaintId = ComplaintRepositoryPort.newComplaintId();
        var record = new ComplaintRepositoryPort.ComplaintRecord(
                complaintId, req.tenantId(), req.userId(), req.orderId(),
                req.category(), req.description(), req.priority(),
                "CREATED", System.currentTimeMillis());
        repo.save(record);

        // 回填幂等
        idempotencyStore.replace(idempotencyKey, complaintId);

        return new ComplaintResponse(complaintId, "CREATED", req.priority(), "投诉已受理，处理时效 24 小时");
    }

    public record ComplaintRequest(
            String tenantId,
            String userId,
            String orderId,
            String category,
            String description,
            String priority
    ) {}

    public record ComplaintResponse(
            String complaintId,
            String status,
            String priority,
            String message
    ) {}
}
