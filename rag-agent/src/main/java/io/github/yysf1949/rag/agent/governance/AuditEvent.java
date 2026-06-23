package io.github.yysf1949.rag.agent.governance;

import java.time.Instant;
import java.util.UUID;

/**
 * 审计事件 — 串联每一次工具调用、业务服务、数据库操作和消息发送，
 * 形成可以完整回放的执行链路。
 *
 * <h2>对齐「路条编程」文章 §"没有可观测性，就不可能放心让 Agent 处理真实业务"</h2>
 * <p>每次工具调用产生一条 AuditEvent，包含 traceId（串联）、脱敏后的输入参数、
 * 输出摘要、结果状态、耗时等。可落库（ELK/Grafana Loki）也可走 Micrometer 指标。</p>
 *
 * @param auditId      唯一审计 ID（UUID）
 * @param traceId      链路追踪 ID（来自 TraceContext）
 * @param tenantId     租户 ID
 * @param userId       用户 ID
 * @param sessionId    会话 ID
 * @param toolName     工具名
 * @param riskLevel    风险等级（L0-L4）
 * @param inputParams  脱敏后的输入参数
 * @param outputSummary 输出摘要（截断后）
 * @param outcome      结果：SUCCESS / FAILURE / HANDOFF / DENIED
 * @param latencyMs    执行耗时（毫秒）
 * @param errorMessage 错误信息（失败时）
 * @param timestamp    事件时间戳
 */
public record AuditEvent(
        String auditId,
        String traceId,
        String tenantId,
        String userId,
        String sessionId,
        String toolName,
        String riskLevel,
        String inputParams,
        String outputSummary,
        String outcome,
        long latencyMs,
        String errorMessage,
        Instant timestamp
) {
    /**
     * 工厂方法 — 生成完整审计事件。
     */
    public static AuditEvent of(
            String traceId,
            String tenantId,
            String userId,
            String sessionId,
            String toolName,
            String riskLevel,
            String inputParams,
            String outputSummary,
            String outcome,
            long latencyMs,
            String errorMessage
    ) {
        return new AuditEvent(
                UUID.randomUUID().toString(),
                traceId,
                tenantId,
                userId,
                sessionId,
                toolName,
                riskLevel,
                inputParams,
                outputSummary,
                outcome,
                latencyMs,
                errorMessage,
                Instant.now()
        );
    }
}
