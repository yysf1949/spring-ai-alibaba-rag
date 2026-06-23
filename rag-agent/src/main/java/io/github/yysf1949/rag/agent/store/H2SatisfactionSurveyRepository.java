package io.github.yysf1949.rag.agent.store;

import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Profile("h2")
public class H2SatisfactionSurveyRepository implements SatisfactionSurveyPort {

    private final JdbcTemplate jdbc;
    private static final RowMapper<SurveyRecord> MAPPER = (rs, row) -> new SurveyRecord(
            rs.getString("survey_id"),
            rs.getString("tenant_id"),
            rs.getString("user_id"),
            rs.getString("conversation_id"),
            rs.getInt("rating"),
            rs.getString("feedback"),
            rs.getBoolean("resolved"),
            rs.getLong("created_at")
    );

    public H2SatisfactionSurveyRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public SurveyRecord save(SurveyRecord record) {
        jdbc.update("MERGE INTO agent_satisfaction_survey (survey_id, tenant_id, user_id, conversation_id, rating, feedback, resolved, created_at) "
                        + "KEY(survey_id) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
                record.surveyId(), record.tenantId(), record.userId(),
                record.conversationId(), record.rating(), record.feedback(),
                record.resolved(), record.createdAt());
        return record;
    }

    @Override
    public List<SurveyRecord> findByConversation(String conversationId) {
        return jdbc.query("SELECT * FROM agent_satisfaction_survey WHERE conversation_id = ?",
                MAPPER, conversationId);
    }

    @Override
    public long countAll() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_satisfaction_survey", Long.class);
        return count == null ? 0 : count;
    }

    @Override
    public long countResolved() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM agent_satisfaction_survey WHERE resolved = TRUE", Long.class);
        return count == null ? 0 : count;
    }
}
