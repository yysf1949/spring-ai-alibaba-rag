package io.github.yysf1949.rag.llm.router;

/**
 * Phase 38: 路由规则类型枚举。
 *
 * <h2>规则优先级 (从高到低)</h2>
 * <ol>
 *   <li><b>PRECISION</b> — 高精度需求 (法律/医疗/金融), 强制使用最高质量模型</li>
 *   <li><b>FAST</b> — 低延迟需求 (实时对话), 优先选择响应最快的模型</li>
 *   <li><b>COST_OPTIMIZED</b> — 成本优先, 选择最便宜的可用模型</li>
 *   <li><b>BALANCED</b> — 默认策略, 平衡质量与成本</li>
 *   <li><b>LONG_CONTEXT</b> — 长文本处理, 选择上下文窗口最大的模型</li>
 * </ol>
 */
public enum RoutingStrategy {
    /** 高精度: 法律/医疗/金融等专业领域 */
    PRECISION("高精度", "precision"),

    /** 低延迟: 实时对话/即时响应 */
    FAST("低延迟", "fast"),

    /** 成本优化: 批量处理/非关键任务 */
    COST_OPTIMIZED("成本优先", "cost"),

    /** 平衡: 默认策略 */
    BALANCED("平衡", "balanced"),

    /** 长上下文: 文档分析/长文本处理 */
    LONG_CONTEXT("长上下文", "long_context");

    private final String displayName;
    private final String code;

    RoutingStrategy(String displayName, String code) {
        this.displayName = displayName;
        this.code = code;
    }

    public String displayName() { return displayName; }
    public String code() { return code; }

    public static RoutingStrategy fromCode(String code) {
        if (code == null) return BALANCED;
        for (RoutingStrategy s : values()) {
            if (s.code.equals(code.toLowerCase())) return s;
        }
        return BALANCED;
    }
}
