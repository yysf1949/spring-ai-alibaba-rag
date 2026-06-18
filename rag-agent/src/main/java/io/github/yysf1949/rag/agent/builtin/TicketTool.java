package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.TicketRepositoryPort;
import io.github.yysf1949.rag.agent.governance.AgentIdentity;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * 提醒工单工具 — L2 可逆低风险。
 *
 * <h2>为什么是 L2</h2>
 * <p>创建工单是"创建草稿、生成工单、创建提醒"类操作 — 不立即影响核心业务
 * （订单/支付/库存），但会写入业务数据库。对应文章 L2 定义："可执行但
 * 结果不应该立即影响核心业务。"</p>
 *
 * <h2>幂等实现</h2>
 * <p>调用方必须传 {@code idempotencyKey}（{@code requiresIdempotencyKey=true}）。
 * 第二次同 key 调用直接返回上次结果，<b>不</b>创建第二张工单。</p>
 */
@Component
public class TicketTool {

    private final TicketRepositoryPort repository;
    private final IdempotencyStore idempotencyStore;

    public TicketTool(TicketRepositoryPort repository, IdempotencyStore idempotencyStore) {
        this.repository = repository;
        this.idempotencyStore = idempotencyStore;
    }

    public record Request(
            String sourceTool,
            String description
    ) {
        public Request {
            if (description == null || description.isBlank()) {
                throw new IllegalArgumentException("description required");
            }
            if (sourceTool == null || sourceTool.isBlank()) sourceTool = "agent";
        }
    }

    public record Response(
            String ticketId,
            String status
    ) { }

    @ToolSpec(
            name = "create_reminder_ticket",
            description = "为当前用户创建一条提醒工单（不修改订单/支付/库存）。"
                    + "适用于'kb_search 返回 FALLBACK_RULE / 用户表示要人工跟进'等场景。"
                    + "调用方必须传 idempotencyKey；同 key 重复调用返回上次结果。",
            riskLevel = RiskLevel.L2_REVERSIBLE,
            idempotent = false,
            requiresIdempotencyKey = true)
    public Response createReminder(AgentIdentity identity, IdempotencyKey idempotencyKey, Request request) {
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            // 第一次写入时是 null —— 但因为是 REPLAY，意味着有上游调用方已创建过。
            // 我们改成"幂等 = 同步锁 + 结果回放"模式：第一次成功后再 write。
            // 简化：本 Phase 把 ticket id 直接放在 idempotencyStore value 里。
            String existingId = (String) put.value();
            if (existingId != null) {
                return new Response(existingId, "PENDING");
            }
        }

        // 第一次：创建 + 回填 idempotency
        String ticketId = "TKT-" + UUID.randomUUID().toString().substring(0, 8);
        TicketRepositoryPort.TicketRecord ticket = new TicketRepositoryPort.TicketRecord(
                ticketId,
                identity.tenantId(),
                identity.userId(),
                request.description(),
                "PENDING",
                System.currentTimeMillis());
        repository.save(ticket);

        // 用相同 key 把 ticketId 写回 — 第二次 REPLAY 时能拿回
        idempotencyStore.replace(idempotencyKey, ticketId);

        return new Response(ticketId, "PENDING");
    }
}
