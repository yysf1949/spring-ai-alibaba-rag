package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.core.model.RetrievedChunk;
import io.github.yysf1949.rag.core.port.RetrievalPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Phase 17 重构后 KbSearchTool 单测 — 验证委托给 RetrievalPort, 不再依赖 QAService。
 */
class KbSearchToolTest {

    @Test
    void delegatesToRetrievalPort() {
        RetrievalPort port = mock(RetrievalPort.class);
        var chunk = new RetrievedChunk(
                "c-1", "用户可在收到货 7 天内退款", 0.92,
                "default", 42L, Map.of("sourceUri", "https://example.com/policy"));
        when(port.search(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(List.of(chunk));

        var tool = new KbSearchTool(port);
        var out = tool.search(new KbSearchTool.Request(
                "tenant1", "default", -1L, "怎么退款",
                5, List.of()));

        assertThat(out.kbId()).isEqualTo("default");
        assertThat(out.query()).isEqualTo("怎么退款");
        assertThat(out.total()).isEqualTo(1);
        assertThat(out.chunks()).hasSize(1);
        assertThat(out.chunks().get(0).id()).isEqualTo("c-1");
        assertThat(out.chunks().get(0).score()).isEqualTo(0.92);
        assertThat(out.chunks().get(0).text()).isEqualTo("用户可在收到货 7 天内退款");
        assertThat(out.chunks().get(0).metadata()).containsEntry("sourceUri", "https://example.com/policy");
    }

    @Test
    void kbVersionMinusOneTranslatedToZero() {
        RetrievalPort port = mock(RetrievalPort.class);
        when(port.search(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(List.of());

        var tool = new KbSearchTool(port);
        tool.search(new KbSearchTool.Request(
                "tenant1", "default", -1L, "x", 5, List.of()));

        // 验证 RetrievalPort.search 收到的 effectiveKbVersion=0 (kbVersion=-1 已转 0)
        org.mockito.Mockito.verify(port).search(
                org.mockito.ArgumentMatchers.eq("tenant1"),
                org.mockito.ArgumentMatchers.eq("default"),
                org.mockito.ArgumentMatchers.eq(0L),
                org.mockito.ArgumentMatchers.eq("x"),
                org.mockito.ArgumentMatchers.eq(5),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void emptyRetrievalReturnsTotalZero() {
        RetrievalPort port = mock(RetrievalPort.class);
        when(port.search(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(List.of());

        var tool = new KbSearchTool(port);
        var out = tool.search(new KbSearchTool.Request(
                "tenant1", "default", -1L, "无答案问题", 5, List.of()));

        assertThat(out.total()).isEqualTo(0);
        assertThat(out.chunks()).isEmpty();
    }

    @Test
    void responseJsonShapeMatchesLlmContract() throws Exception {
        // Plan §2.4 — LLM 拿到的 JSON 字段顺序: kbId, query, total, chunks[].id/text/score/kbId/kbVersion/metadata
        RetrievalPort port = mock(RetrievalPort.class);
        var chunk = new RetrievedChunk(
                "c-1", "用户可在 7 天内退款", 0.92,
                "default", 42L, Map.of("sourceUri", "https://example.com/p"));
        when(port.search(anyString(), anyString(), anyLong(), anyString(), anyInt(), any()))
                .thenReturn(List.of(chunk));

        var tool = new KbSearchTool(port);
        var resp = tool.search(new KbSearchTool.Request(
                "tenant1", "default", -1L, "退款", 5, List.of()));

        // 验证顶层字段名 (Jackson 序列化顺序由 record 声明顺序决定)
        var json = new ObjectMapper().writeValueAsString(resp);
        assertThat(json).contains("\"kbId\":\"default\"");
        assertThat(json).contains("\"query\":\"退款\"");
        assertThat(json).contains("\"total\":1");
        assertThat(json).contains("\"id\":\"c-1\"");
        assertThat(json).contains("\"text\":\"用户可在 7 天内退款\"");
        assertThat(json).contains("\"score\":0.92");
        assertThat(json).contains("\"kbVersion\":42");
        assertThat(json).contains("\"metadata\":");
        // 字段顺序断言: kbId 必须在 query 之前
        assertThat(json.indexOf("kbId")).isLessThan(json.indexOf("query"));
        assertThat(json.indexOf("query")).isLessThan(json.indexOf("total"));
        assertThat(json.indexOf("total")).isLessThan(json.indexOf("chunks"));
    }
}