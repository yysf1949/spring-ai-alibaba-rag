package io.github.yysf1949.rag.agent.action;

import java.util.Arrays;

/**
 * Tool 风险分级 — 对齐「路条编程」AI 客服文章 §"查询 ≠ 执行，必须拆开" 节。
 *
 * <h2>4 级定义</h2>
 * <ul>
 *   <li><b>L1_READ</b> — 只读工具（查询订单、查询物流、查询退款规则）。可自动执行。</li>
 *   <li><b>L2_REVERSIBLE</b> — 可逆低风险（创建草稿、生成工单、创建提醒）。
 *       可执行但结果不立即影响核心业务。</li>
 *   <li><b>L3_BUSINESS_STATE</b> — 改业务态（取消订单、创建退款、补发优惠券）。
 *       必须有幂等机制，且需用户二次确认。</li>
 *   <li><b>L4_HIGH_RISK</b> — 高风险（人工改价、直接退款、修改用户权限、删除数据）。
 *       不应直接由模型执行，必须走人工审批。</li>
 * </ul>
 *
 * <h2>升级路径</h2>
 * <p>本枚举是 Action Layer 的稳定契约。Spring AI 版本升级（1.0.9 → 2.0+）不影响。</p>
 */
public enum RiskLevel {

    L1_READ,
    L2_REVERSIBLE,
    L3_BUSINESS_STATE,
    L4_HIGH_RISK;

    /**
     * 该风险级是否需要用户二次确认才能执行。
     *
     * <p>对应文章论断："低风险自动 / 高风险辅助（Agent 在转人工前把
     * 身份、订单、规则、风险说明都准备好）。"</p>
     */
    public boolean requiresConfirmation() {
        return this == L3_BUSINESS_STATE || this == L4_HIGH_RISK;
    }

    /**
     * 严格大小写不敏感的解析，调用方拿到的可能是模型输出的字符串。
     */
    public static RiskLevel parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("RiskLevel raw is null");
        }
        String normalized = raw.trim().toUpperCase();
        return Arrays.stream(values())
                .filter(l -> l.name().equals(normalized))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown RiskLevel: " + raw + ", expected one of " + Arrays.toString(values())));
    }
}