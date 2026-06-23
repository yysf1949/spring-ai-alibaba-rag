package io.github.yysf1949.rag.agent.orchestration;

/**
 * Agent Loop 调试模式 — 方便开发时观察每一步的决策过程。
 *
 * <ul>
 *   <li>{@link #OFF} — 正常模式，不记录任何调试事件。</li>
 *   <li>{@link #RECORD} — 记录事件到内存列表，但不输出到控制台。</li>
 *   <li>{@link #VERBOSE} — 记录事件 + 每步打印到 stderr。</li>
 * </ul>
 */
public enum DebugMode {

    /** 正常模式，不记录调试事件。 */
    OFF,

    /** 记录事件但不输出到控制台。 */
    RECORD,

    /** 记录事件 + 每步打印到 stderr。 */
    VERBOSE
}
