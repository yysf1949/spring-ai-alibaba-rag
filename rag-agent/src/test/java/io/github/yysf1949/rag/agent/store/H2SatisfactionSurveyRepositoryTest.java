package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 满意度调查 H2 集成测试 — 使用真实 H2 内存数据库，非 mock。
 */
class H2SatisfactionSurveyRepositoryTest {

    private static JdbcTemplate jdbc;
    private static H2SatisfactionSurveyRepository repo;

    @BeforeAll
    static void setUp() {
        DataSource ds = new DriverManagerDataSource("jdbc:h2:mem:test_survey_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(ds);
        StoreAutoConfiguration.ensureAllSchema(jdbc);
        repo = new H2SatisfactionSurveyRepository(jdbc);
    }

    @Test
    void saveAndFindByConversation() {
        var record = new SatisfactionSurveyPort.SurveyRecord(
                "SRV-001", "t1", "u1", "conv-123",
                5, "非常满意，问题解决很快", true, 1700000000000L);

        repo.save(record);

        var found = repo.findByConversation("conv-123");
        assertThat(found).hasSize(1);
        assertThat(found.get(0).surveyId()).isEqualTo("SRV-001");
        assertThat(found.get(0).tenantId()).isEqualTo("t1");
        assertThat(found.get(0).userId()).isEqualTo("u1");
        assertThat(found.get(0).conversationId()).isEqualTo("conv-123");
        assertThat(found.get(0).rating()).isEqualTo(5);
        assertThat(found.get(0).feedback()).isEqualTo("非常满意，问题解决很快");
        assertThat(found.get(0).resolved()).isTrue();
        assertThat(found.get(0).createdAt()).isEqualTo(1700000000000L);
    }

    @Test
    void findByConversationReturnsEmptyWhenNone() {
        var found = repo.findByConversation("conv-nonexistent");
        assertThat(found).isEmpty();
    }

    @Test
    void multipleRecordsForSameConversation() {
        repo.save(new SatisfactionSurveyPort.SurveyRecord(
                "SRV-002", "t1", "u2", "conv-456",
                3, "一般般", false, 1700000000000L));
        repo.save(new SatisfactionSurveyPort.SurveyRecord(
                "SRV-003", "t1", "u3", "conv-456",
                4, "不错", true, 1700000060000L));

        var found = repo.findByConversation("conv-456");
        assertThat(found).hasSize(2);
        assertThat(found).extracting(SatisfactionSurveyPort.SurveyRecord::surveyId)
                .containsExactlyInAnyOrder("SRV-002", "SRV-003");
    }
}
