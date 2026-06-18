package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.core.model.Answer;
import io.github.yysf1949.rag.core.model.AnswerSource;
import io.github.yysf1949.rag.core.model.Query;
import io.github.yysf1949.rag.core.port.QAService;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KbSearchToolTest {

    @Test
    void delegatesToQAService() {
        QAService qa = mock(QAService.class);
        // Answer record 字段顺序: tenantId, queryHash, rewrittenQuery, retrieved, reranked,
        //   finalText, citations, source, latencyMs, metrics
        // 这里"snippet"映射到 finalText（用户可见答案），"LLM"作为 source，latencyMs 填 0。
        var answer = new Answer(
                "tenant1", "qhash", "怎么退款",
                java.util.List.of(), java.util.List.of(),
                "snippet", java.util.List.of(),
                AnswerSource.LLM, 0L, java.util.Map.of());
        when(qa.answer(any(Query.class))).thenReturn(answer);

        var tool = new KbSearchTool(qa);
        var out = tool.search(new KbSearchTool.Request("tenant1", "user1", "怎么退款", null, 5, null));

        assertThat(out.answerText()).isEqualTo("snippet");
        assertThat(out.source()).isEqualTo(AnswerSource.LLM);
    }
}
