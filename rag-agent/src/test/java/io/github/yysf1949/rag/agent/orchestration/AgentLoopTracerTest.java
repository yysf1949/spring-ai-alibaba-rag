package io.github.yysf1949.rag.agent.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * AgentLoopTracer 单元测试。
 *
 * <p>验证调试模式开/关、JSON 输出格式、各事件类型的记录行为。</p>
 */
class AgentLoopTracerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    // ── 测试 1: 调试模式开启时记录工具选择 ──────────────────────

    @Test
    void debugModeOn_logsToolSelection() {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        List<String> candidates = List.of("query_order", "create_refund", "confirm_refund");
        List<String> selected = List.of("query_order");
        tracer.logToolSelection(candidates, selected);

        List<String> logs = tracer.getCapturedLogs();
        assertThat(logs).hasSize(1);

        String json = logs.get(0);
        assertThat(json).contains("TOOL_SELECTION");
        assertThat(json).contains("query_order");
        assertThat(json).contains("create_refund");
    }

    // ── 测试 2: 调试模式关闭时无记录（零开销） ──────────────────

    @Test
    void debugModeOff_noLogsRecorded() {
        AgentLoopTracer tracer = new AgentLoopTracer(false);

        tracer.logToolSelection(List.of("query_order"), List.of("query_order"));
        tracer.logRiskGate("query_order", "L1_READ", "ALLOW", null);
        tracer.logIdempotency("key-1", false);
        tracer.logExecution("query_order", "{}", "{}", 100, "SUCCESS");

        assertThat(tracer.getCapturedLogs()).isEmpty();
        assertThat(tracer.isEnabled()).isFalse();
    }

    // ── 测试 3: JSON 输出格式正确（工具选择事件） ────────────────

    @Test
    void toolSelection_jsonFormatIsCorrect() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logToolSelection(
                List.of("query_order", "create_refund"),
                List.of("query_order"));

        String json = tracer.getCapturedLogs().get(0);
        JsonNode node = objectMapper.readTree(json);

        assertThat(node.get("event").asText()).isEqualTo("TOOL_SELECTION");
        assertThat(node.has("timestamp")).isTrue();
        assertThat(node.get("candidates")).hasSize(2);
        assertThat(node.get("selected")).hasSize(1);
        assertThat(node.get("selected").get(0).asText()).isEqualTo("query_order");
        assertThat(node.get("filtered")).hasSize(1);
        assertThat(node.get("filtered").get(0).asText()).isEqualTo("create_refund");
    }

    // ── 测试 4: 风险门控拒绝记录 ──────────────────────────────

    @Test
    void riskGate_deniedEventRecorded() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logRiskGate("create_refund", "L3_BUSINESS_STATE", "DENY",
                "Missing idempotency key for L3 tool");

        List<String> logs = tracer.getCapturedLogs();
        assertThat(logs).hasSize(1);

        JsonNode node = objectMapper.readTree(logs.get(0));
        assertThat(node.get("event").asText()).isEqualTo("RISK_GATE");
        assertThat(node.get("tool").asText()).isEqualTo("create_refund");
        assertThat(node.get("riskLevel").asText()).isEqualTo("L3_BUSINESS_STATE");
        assertThat(node.get("decision").asText()).isEqualTo("DENY");
        assertThat(node.get("reason").asText()).isEqualTo("Missing idempotency key for L3 tool");
    }

    // ── 测试 5: 风险门控通过记录 ──────────────────────────────

    @Test
    void riskGate_allowedEventRecorded() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logRiskGate("query_order", "L1_READ", "ALLOW", null);

        JsonNode node = objectMapper.readTree(tracer.getCapturedLogs().get(0));
        assertThat(node.get("event").asText()).isEqualTo("RISK_GATE");
        assertThat(node.get("decision").asText()).isEqualTo("ALLOW");
        assertThat(node.has("reason")).isFalse(); // null reason 不输出字段
    }

    // ── 测试 6: 幂等 replay 记录 ─────────────────────────────

    @Test
    void idempotency_replayEventRecorded() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logIdempotency("tenant-1:user-1:create_refund:tok-1", true);

        JsonNode node = objectMapper.readTree(tracer.getCapturedLogs().get(0));
        assertThat(node.get("event").asText()).isEqualTo("IDEMPOTENCY_CHECK");
        assertThat(node.get("idempotencyKey").asText())
                .isEqualTo("tenant-1:user-1:create_refund:tok-1");
        assertThat(node.get("isReplay").asBoolean()).isTrue();
        assertThat(node.get("action").asText()).isEqualTo("RETURN_CACHED");
    }

    // ── 测试 7: 幂等首次执行记录 ─────────────────────────────

    @Test
    void idempotency_firstExecutionRecorded() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logIdempotency("key-new", false);

        JsonNode node = objectMapper.readTree(tracer.getCapturedLogs().get(0));
        assertThat(node.get("event").asText()).isEqualTo("IDEMPOTENCY_CHECK");
        assertThat(node.get("isReplay").asBoolean()).isFalse();
        assertThat(node.get("action").asText()).isEqualTo("FIRST_EXECUTION");
    }

    // ── 测试 8: 工具执行结果记录 ──────────────────────────────

    @Test
    void execution_successEventRecorded() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logExecution("query_order", "{\"orderId\":\"ORD-001\"}",
                "{\"status\":\"PAID\"}", 42, "SUCCESS");

        JsonNode node = objectMapper.readTree(tracer.getCapturedLogs().get(0));
        assertThat(node.get("event").asText()).isEqualTo("TOOL_EXECUTION");
        assertThat(node.get("tool").asText()).isEqualTo("query_order");
        assertThat(node.get("durationMs").asLong()).isEqualTo(42);
        assertThat(node.get("outcome").asText()).isEqualTo("SUCCESS");
        assertThat(node.get("args").asText()).contains("ORD-001");
        assertThat(node.get("result").asText()).contains("PAID");
    }

    // ── 测试 9: 工具执行失败记录 ──────────────────────────────

    @Test
    void execution_failureEventRecorded() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logExecution("create_refund", "{}", null, 150, "FAILURE");

        JsonNode node = objectMapper.readTree(tracer.getCapturedLogs().get(0));
        assertThat(node.get("event").asText()).isEqualTo("TOOL_EXECUTION");
        assertThat(node.get("outcome").asText()).isEqualTo("FAILURE");
        assertThat(node.get("durationMs").asLong()).isEqualTo(150);
        assertThat(node.has("result")).isFalse(); // null result 不输出
    }

    // ── 测试 10: 多步记录顺序正确 ────────────────────────────

    @Test
    void multipleSteps_recordedInOrder() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        // 模拟一个完整的 Agent Loop 流程
        tracer.logToolSelection(List.of("query_order", "create_refund"), List.of("create_refund"));
        tracer.logRiskGate("create_refund", "L3_BUSINESS_STATE", "ALLOW", null);
        tracer.logIdempotency("tok-1", false);
        tracer.logExecution("create_refund", "{}", "{\"refundId\":\"RF-001\"}", 85, "SUCCESS");

        List<String> logs = tracer.getCapturedLogs();
        assertThat(logs).hasSize(4);

        // 验证事件顺序
        JsonNode step1 = objectMapper.readTree(logs.get(0));
        JsonNode step2 = objectMapper.readTree(logs.get(1));
        JsonNode step3 = objectMapper.readTree(logs.get(2));
        JsonNode step4 = objectMapper.readTree(logs.get(3));

        assertThat(step1.get("event").asText()).isEqualTo("TOOL_SELECTION");
        assertThat(step2.get("event").asText()).isEqualTo("RISK_GATE");
        assertThat(step3.get("event").asText()).isEqualTo("IDEMPOTENCY_CHECK");
        assertThat(step4.get("event").asText()).isEqualTo("TOOL_EXECUTION");

        // 验证时间戳递增
        long ts1 = step1.get("timestamp").asLong();
        long ts2 = step2.get("timestamp").asLong();
        long ts3 = step3.get("timestamp").asLong();
        long ts4 = step4.get("timestamp").asLong();
        assertThat(ts2).isGreaterThanOrEqualTo(ts1);
        assertThat(ts3).isGreaterThanOrEqualTo(ts2);
        assertThat(ts4).isGreaterThanOrEqualTo(ts3);
    }

    // ── 测试 11: 风险门控 HANDOFF 记录 ────────────────────────

    @Test
    void riskGate_handoffEventRecorded() throws Exception {
        AgentLoopTracer tracer = new AgentLoopTracer(true);

        tracer.logRiskGate("create_refund", "L3_BUSINESS_STATE", "HANDOFF",
                "Amount limit exceeded: requested=600000 limit=500000");

        JsonNode node = objectMapper.readTree(tracer.getCapturedLogs().get(0));
        assertThat(node.get("decision").asText()).isEqualTo("HANDOFF");
        assertThat(node.get("reason").asText()).contains("Amount limit exceeded");
    }

    // ── 测试 12: enabled 状态查询 ────────────────────────────

    @Test
    void isEnabled_reflectsConstructorParam() {
        AgentLoopTracer enabled = new AgentLoopTracer(true);
        AgentLoopTracer disabled = new AgentLoopTracer(false);

        assertThat(enabled.isEnabled()).isTrue();
        assertThat(disabled.isEnabled()).isFalse();
    }
}
