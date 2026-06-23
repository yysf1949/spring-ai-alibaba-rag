package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

/**
 * 投诉工单 Port — 独立于普通 Ticket 的投诉通道。
 *
 * <p>投诉比普通工单严重：有分类（服务态度/商品质量/物流问题）、
 * 有优先级（P0-P3）、需要指定处理时限。对齐文章"创建工单"中的高级场景。</p>
 */
public interface ComplaintRepositoryPort {

    ComplaintRecord save(ComplaintRecord complaint);

    Optional<ComplaintRecord> findByIdAndTenant(String complaintId, String tenantId);

    record ComplaintRecord(
            String complaintId,
            String tenantId,
            String userId,
            String orderId,
            String category,
            String description,
            String priority,
            String status,
            long createdAt
    ) {}

    static String newComplaintId() {
        return "CMP-" + java.util.UUID.randomUUID().toString().substring(0, 8);
    }
}
