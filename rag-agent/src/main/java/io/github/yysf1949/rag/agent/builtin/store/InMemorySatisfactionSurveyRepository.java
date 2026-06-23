package io.github.yysf1949.rag.agent.builtin.store;
import org.springframework.context.annotation.Profile;

import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 满意度调查内存仓库 — 教学 demo 用。
 *
 * <h2>升级路径</h2>
 * <p>生产可换 MySQL/Postgres + Flyway 迁移。本 Phase 范围只覆盖满意度调查的
 * 端到端跑通。</p>
 */
@Component
@Profile("default")
public class InMemorySatisfactionSurveyRepository implements SatisfactionSurveyPort {

    private final Map<String, SatisfactionSurveyPort.SurveyRecord> store = new ConcurrentHashMap<>();

    @Override
    public SatisfactionSurveyPort.SurveyRecord save(SatisfactionSurveyPort.SurveyRecord record) {
        store.put(record.surveyId(), record);
        return record;
    }

    @Override
    public List<SatisfactionSurveyPort.SurveyRecord> findByConversation(String conversationId) {
        List<SatisfactionSurveyPort.SurveyRecord> out = new ArrayList<>();
        for (SatisfactionSurveyPort.SurveyRecord r : store.values()) {
            if (r.conversationId().equals(conversationId)) {
                out.add(r);
            }
        }
        return out;
    }

    @Override
    public long countAll() {
        return store.size();
    }

    @Override
    public long countResolved() {
        return store.values().stream().filter(SatisfactionSurveyPort.SurveyRecord::resolved).count();
    }
}
