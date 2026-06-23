package io.github.yysf1949.rag.agent.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.FeedbackRecord;
import io.github.yysf1949.rag.agent.feedback.FeedbackPort.Thumb;
import io.github.yysf1949.rag.agent.feedback.store.InMemoryFeedbackRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link FeedbackExportController} 测试 — MockMvc standalone 模式 + 直接驱动
 * StreamingResponseBody 输出.
 *
 * <p>覆盖: Admin 鉴权 (缺 key 403 / 错 key 403 / 对 key 200) + 流式输出 100 records
 * 不爆 OOM + tenant filter 正确 + JSONL 行数等于反馈数 + Content-Type/Disposition.</p>
 */
class FeedbackExportControllerTest {

    private static final String ADMIN_KEY = "test-admin-secret-xyz";

    private FeedbackPort port;
    private MockMvc mvc;
    private final ObjectMapper om = new ObjectMapper();

    @BeforeEach
    void setUp() {
        port = new InMemoryFeedbackRepository();
        FeedbackExportController controller = new FeedbackExportController(port, om, ADMIN_KEY);
        mvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new AgentExceptionHandler())
                .build();
    }

    @Test
    void missingAdminKeyReturns403() throws Exception {
        mvc.perform(get("/api/admin/feedback/export")
                        .param("tenantId", "tenant-a"))
                .andExpect(status().isForbidden());
    }

    @Test
    void wrongAdminKeyReturns403() throws Exception {
        mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", "wrong-key")
                        .param("tenantId", "tenant-a"))
                .andExpect(status().isForbidden());
    }

    @Test
    void correctAdminKeyReturns200Ndjson() throws Exception {
        seedFeedback("tenant-a", 3);

        MvcResult result = mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "tenant-a"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type",
                        "application/x-ndjson"))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("attachment")))
                .andReturn();

        List<JsonNode> lines = parseNdjson(result.getResponse().getContentAsByteArray());
        assertThat(lines).hasSize(3);
        JsonNode first = lines.get(0);
        assertThat(first.get("feedback_id").asText()).startsWith("FB-");
        assertThat(first.get("tenant_id").asText()).isEqualTo("tenant-a");
        // seedFeedback 用 i=0..n-1 递增 userId; createdAt 升序返回时 first=userId u0.
        // 这里只断言 userId 格式合法 + 含 u 前缀, 不锁具体值 (避免对排序细节过度耦合).
        assertThat(first.get("user_id").asText()).startsWith("u");
    }

    @Test
    void streamingExportOf100RecordsDoesNotOom() throws Exception {
        // DoD: "100 records 流式导出 < 1s, 不爆 OOM"
        // 100 records 一次性塞内存是 OK 的 (T2 没要求 1M records 级别),
        // 这里关键是验证 StreamingResponseBody 真的被驱动到写出.
        seedFeedback("tenant-a", 100);

        long start = System.currentTimeMillis();
        MvcResult result = mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "tenant-a"))
                .andExpect(status().isOk())
                .andReturn();
        long elapsedMs = System.currentTimeMillis() - start;

        List<JsonNode> lines = parseNdjson(result.getResponse().getContentAsByteArray());
        assertThat(lines).hasSize(100);
        // 验证每行都有完整字段 (不能是截断的)
        for (JsonNode line : lines) {
            assertThat(line.has("feedback_id")).isTrue();
            assertThat(line.has("tenant_id")).isTrue();
            assertThat(line.has("created_at")).isTrue();
            assertThat(line.has("agent_identity")).isTrue();
        }
        assertThat(elapsedMs).as("export 100 records should be < 1000ms")
                .isLessThan(1000L);
    }

    @Test
    void tenantFilterIsolatesData() throws Exception {
        // DoD: "tenant filter 正确"
        seedFeedback("tenant-a", 5);
        seedFeedback("tenant-b", 3);

        MvcResult resultA = mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "tenant-a"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult resultB = mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "tenant-b"))
                .andExpect(status().isOk())
                .andReturn();

        List<JsonNode> linesA = parseNdjson(resultA.getResponse().getContentAsByteArray());
        List<JsonNode> linesB = parseNdjson(resultB.getResponse().getContentAsByteArray());

        assertThat(linesA).hasSize(5);
        assertThat(linesB).hasSize(3);
        assertThat(linesA).allMatch(n -> "tenant-a".equals(n.get("tenant_id").asText()));
        assertThat(linesB).allMatch(n -> "tenant-b".equals(n.get("tenant_id").asText()));
    }

    @Test
    void emptyTenantReturnsEmptyBody() throws Exception {
        MvcResult result = mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "tenant-empty"))
                .andExpect(status().isOk())
                .andReturn();
        byte[] body = result.getResponse().getContentAsByteArray();
        assertThat(body).isEmpty();
    }

    @Test
    void timeRangeFilterApplies() throws Exception {
        // 3 records 在 t=1000/2000/3000
        FeedbackRecord r1 = new FeedbackRecord(
                "FB-1", "t1", "u1", "c1", null, Thumb.UP, null, null, "api", null, 1000L);
        FeedbackRecord r2 = new FeedbackRecord(
                "FB-2", "t1", "u1", "c2", null, Thumb.DOWN, null, null, "api", null, 2000L);
        FeedbackRecord r3 = new FeedbackRecord(
                "FB-3", "t1", "u1", "c3", null, null, 5, null, "api", null, 3000L);
        port.save(r1);
        port.save(r2);
        port.save(r3);

        // [1500, 2500] → 只有 r2
        MvcResult result = mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "t1")
                        .param("from", "1500")
                        .param("to", "2500"))
                .andExpect(status().isOk())
                .andReturn();

        List<JsonNode> lines = parseNdjson(result.getResponse().getContentAsByteArray());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("feedback_id").asText()).isEqualTo("FB-2");
    }

    @Test
    void unsupportedFormatReturns500FromIae() throws Exception {
        // IAE 走兜底 (RagExceptionHandler.unexpected) → 500, 不要污染 4xx 路径
        mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "t1")
                        .param("format", "csv"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void fromAfterToReturns500FromIae() throws Exception {
        mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "t1")
                        .param("from", "2000")
                        .param("to", "1000"))
                .andExpect(status().isInternalServerError());
    }

    @Test
    void iso8601TimeAccepted() throws Exception {
        port.save(new FeedbackRecord(
                "FB-iso", "t1", "u1", "c1", null, Thumb.UP, null, null, "api", null,
                java.time.Instant.parse("2026-06-01T00:00:00Z").toEpochMilli()));

        MvcResult result = mvc.perform(get("/api/admin/feedback/export")
                        .header("X-Admin-Key", ADMIN_KEY)
                        .param("tenantId", "t1")
                        .param("from", "2026-05-01T00:00:00Z")
                        .param("to", "2026-07-01T00:00:00Z"))
                .andExpect(status().isOk())
                .andReturn();

        List<JsonNode> lines = parseNdjson(result.getResponse().getContentAsByteArray());
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0).get("feedback_id").asText()).isEqualTo("FB-iso");
    }

    // ---- helpers ----

    private void seedFeedback(String tenantId, int n) {
        for (int i = 0; i < n; i++) {
            port.save(new FeedbackRecord(
                    "FB-" + i + "-" + tenantId,
                    tenantId,
                    "u" + i,
                    "conv-" + i,
                    "msg-" + i,
                    i % 2 == 0 ? Thumb.UP : Thumb.DOWN,
                    (i % 5) + 1,
                    i % 3 == 0 ? "comment " + i : null,
                    i % 2 == 0 ? "web" : "api",
                    i % 4 == 0 ? "v1.0" : null,
                    1_700_000_000_000L + i));
        }
    }

    /**
     * 解析 NDJSON 响应 — 一行一条 JSON, 用流式 BufferedReader 避免一次性吃大字符串.
     */
    private List<JsonNode> parseNdjson(byte[] body) throws Exception {
        List<JsonNode> out = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new java.io.ByteArrayInputStream(body), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) continue;
                out.add(om.readTree(line));
            }
        }
        return out;
    }
}