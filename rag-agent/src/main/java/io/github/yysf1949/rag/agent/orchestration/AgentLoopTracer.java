package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * AgentLoop 调试/追踪器 — 记录 AgentLoop 每一步的决策过程。
 *
 * <p>纯 Java 类，无 Spring 依赖。输出结构化 JSON 日志（SLF4J）。</p>
 *
 * <h2>设计原则</h2>
 * <ul>
 *   <li>禁用时完全不执行记录逻辑（零性能开销）</li>
 *   <li>通过构造函数参数 {@code enabled} 控制开关</li>
 *   <li>内部维护 capturedLogs 列表，方便单元测试验证</li>
 * </ul>
 *
 * <h2>记录内容</h2>
 * <ul>
 *   <li>工具选择 — 哪些工具被选中，哪些被过滤</li>
 *   <li>风险门控 — 通过/拒绝，原因</li>
 *   <li>幂等检查 — 首次执行/返回缓存</li>
 *   <li>工具执行结果 — 成功/失败，耗时</li>
 * </ul>
 */
public class AgentLoopTracer {

    private static final Logger log = LoggerFactory.getLogger(AgentLoopTracer.class);

    private final boolean enabled;
    private final ObjectMapper objectMapper;

    /**
     * 内部日志捕获列表，用于单元测试验证。
     * 生产环境不使用此字段。
     */
    private final List<String> capturedLogs = new ArrayList<>();

    /**
     * @param enabled 是否启用追踪（false 时所有 log 方法直接 return，零开销）
     */
    public AgentLoopTracer(boolean enabled) {
        this.enabled = enabled;
        this.objectMapper = new ObjectMapper();
    }

    /**
     * @param enabled   是否启用追踪
     * @param objectMapper 自定义 ObjectMapper（测试用）
     */
    public AgentLoopTracer(boolean enabled, ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.objectMapper = objectMapper;
    }

    // ── 查询状态 ──────────────────────────────────────────────

    /** 追踪器是否启用。 */
    public boolean isEnabled() {
        return enabled;
    }

    /** 返回已捕获的日志条目（不可变视图），仅用于测试。 */
    public List<String> getCapturedLogs() {
        return Collections.unmodifiableList(new ArrayList<>(capturedLogs));
    }

    // ── 记录方法 ──────────────────────────────────────────────

    /**
     * 记录工具选择事件。
     *
     * @param candidates 所有候选工具名
     * @param selected   最终选中的工具名
     */
    public void logToolSelection(List<String> candidates, List<String> selected) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", "TOOL_SELECTION");
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("candidates", candidates);
        entry.put("selected", selected);
        entry.put("filtered", candidates.stream()
                .filter(c -> !selected.contains(c))
                .toList());

        emit(entry);
    }

    /**
     * 记录风险门控决策。
     *
     * @param tool     工具名
     * @param level    风险等级
     * @param decision 决策结果（ALLOW / DENY / HANDOFF）
     * @param reason   决策原因（可为 null）
     */
    public void logRiskGate(String tool, String level, String decision, String reason) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", "RISK_GATE");
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("tool", tool);
        entry.put("riskLevel", level);
        entry.put("decision", decision);
        if (reason != null) {
            entry.put("reason", reason);
        }

        emit(entry);
    }

    /**
     * 记录幂等检查。
     *
     * @param key      幂等键
     * @param isReplay 是否为回放（true=返回缓存, false=首次执行）
     */
    public void logIdempotency(String key, boolean isReplay) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", "IDEMPOTENCY_CHECK");
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("idempotencyKey", key);
        entry.put("isReplay", isReplay);
        entry.put("action", isReplay ? "RETURN_CACHED" : "FIRST_EXECUTION");

        emit(entry);
    }

    /**
     * 记录工具执行结果。
     *
     * @param tool       工具名
     * @param args       工具参数（JSON 字符串）
     * @param result     执行结果（JSON 字符串，可为 null）
     * @param durationMs 执行耗时（毫秒）
     * @param outcome    执行结果（SUCCESS / FAILURE / DENIED 等）
     */
    public void logExecution(String tool, String args, String result,
                             long durationMs, String outcome) {
        if (!enabled) return;

        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("event", "TOOL_EXECUTION");
        entry.put("timestamp", System.currentTimeMillis());
        entry.put("tool", tool);
        entry.put("durationMs", durationMs);
        entry.put("outcome", outcome);
        if (args != null) {
            entry.put("args", truncate(args, 1000));
        }
        if (result != null) {
            entry.put("result", truncate(result, 1000));
        }

        emit(entry);
    }

    // ── 内部方法 ──────────────────────────────────────────────

    /**
     * 将条目序列化为 JSON 并记录到 SLF4J + capturedLogs。
     */
    private void emit(Map<String, Object> entry) {
        String json;
        try {
            json = objectMapper.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            json = "{\"event\":\"TRACE_ERROR\",\"error\":\"" + e.getMessage() + "\"}";
        }
        capturedLogs.add(json);
        log.info(json);
    }

    /**
     * 截断过长字符串，避免日志爆炸。
     */
    private static String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...(truncated)";
    }
}
