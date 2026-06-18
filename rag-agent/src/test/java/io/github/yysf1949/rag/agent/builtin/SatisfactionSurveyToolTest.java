package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import io.github.yysf1949.rag.agent.builtin.store.InMemorySatisfactionSurveyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SatisfactionSurveyToolTest {

    private InMemorySatisfactionSurveyRepository repo;
    private SatisfactionSurveyTool tool;

    @BeforeEach
    void setUp() {
        repo = new InMemorySatisfactionSurveyRepository();
        tool = new SatisfactionSurveyTool(repo);
    }

    @Test
    void submitSurveyReturnsSurveyId() {
        var resp = tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                "tenant-1", "user-1", "conv-1", 5, "非常满意", true));
        assertThat(resp.surveyId()).startsWith("SRV-");
        assertThat(resp.rating()).isEqualTo(5);
        assertThat(resp.resolved()).isTrue();
    }

    @Test
    void submitSurveyRatingBelowRangeThrows() {
        assertThatThrownBy(() ->
                tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                        "tenant-1", "user-1", "conv-1", 0, "bad", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 5");
    }

    @Test
    void submitSurveyRatingAboveRangeThrows() {
        assertThatThrownBy(() ->
                tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                        "tenant-1", "user-1", "conv-1", 6, "bad", false)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("between 1 and 5");
    }

    @Test
    void submitSurveyRatingBoundaryLow() {
        var resp = tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                "tenant-1", "user-1", "conv-1", 1, "很差", false));
        assertThat(resp.rating()).isEqualTo(1);
        assertThat(resp.resolved()).isFalse();
    }

    @Test
    void submitSurveyRatingBoundaryHigh() {
        var resp = tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                "tenant-1", "user-1", "conv-1", 5, "很好", true));
        assertThat(resp.rating()).isEqualTo(5);
    }

    @Test
    void submitSurveyResolvedFlag() {
        var resp = tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                "tenant-1", "user-1", "conv-1", 4, "已解决", true));
        assertThat(resp.resolved()).isTrue();

        var resp2 = tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                "tenant-1", "user-1", "conv-2", 2, "未解决", false));
        assertThat(resp2.resolved()).isFalse();
    }

    @Test
    void submitSurveyPersisted() {
        var resp = tool.submitSurvey(new SatisfactionSurveyTool.SurveyRequest(
                "tenant-1", "user-1", "conv-1", 4, "good", true));
        var records = tool.listByConversation("conv-1");
        assertThat(records).hasSize(1);
        assertThat(records.get(0).surveyId()).isEqualTo(resp.surveyId());
        assertThat(records.get(0).rating()).isEqualTo(4);
    }
}
