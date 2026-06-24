package io.github.yysf1949.rag.agent.web;

/**
 * Phase 40 T2 — 反馈导出 (JSONL) 配置错误或不可恢复错误。
 *
 * <p>与 {@link FeedbackValidationException} 区分：本异常表示导出参数 (时间区间
 * 反转、format 不支持等) 在管理员调用时被拒绝；属于"管理员操作失误 → 500"语义，
 * 由 {@link AgentExceptionHandler#handleFeedbackExport} 单独映射，避免污染
 * 其它 IllegalArgumentException → 4xx 路径。</p>
 *
 * <p>为何不直接用 IAE：全局 IAE 在 Spring 默认 {@code ResponseEntityExceptionHandler}
 * 下会被映射为 400，会让 admin 的"参数错误"和"用户提交反馈错误"混在一起，
 * 失去上下文定位。</p>
 */
public class FeedbackExportException extends RuntimeException {

    public FeedbackExportException(String message) {
        super(message);
    }

    public FeedbackExportException(String message, Throwable cause) {
        super(message, cause);
    }
}
