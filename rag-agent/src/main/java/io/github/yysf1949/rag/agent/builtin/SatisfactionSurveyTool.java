package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import io.github.yysf1949.rag.agent.governance.IdempotencyKey;
import io.github.yysf1949.rag.agent.governance.IdempotencyStore;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Objects;

/**
 * 满意度调查工具 — L2 可逆（写入评分数据，但不影响核心业务态）。
 *
 * <p>会话结束时收集用户满意度评分和反馈，用于服务质量监控和持续改进。</p>
 */
@Component
public class SatisfactionSurveyTool {

    private final SatisfactionSurveyPort repo;
    private final IdempotencyStore idempotencyStore;

    public SatisfactionSurveyTool(SatisfactionSurveyPort repo, IdempotencyStore idempotencyStore) {
        this.repo = Objects.requireNonNull(repo, "repo");
        this.idempotencyStore = Objects.requireNonNull(idempotencyStore, "idempotencyStore");
    }

    @ToolSpec(
            name = "submit_satisfaction_survey",
            description = "提交满意度调查，返回surveyId/rating/resolved/message。评分1-5+反馈+是否已解决。用户说'问题解决了给5分'、'请给本次服务打个分'。幂等。",
            riskLevel = RiskLevel.L2_REVERSIBLE,
            idempotent = true,
            requiresIdempotencyKey = true
    )
    public SurveyResponse submitSurvey(IdempotencyKey idempotencyKey, SurveyRequest req) {
        Objects.requireNonNull(req, "req");

        // 幂等检查 — 文章: '即使模型重复调用，系统也应该返回第一次的执行结果'
        IdempotencyStore.PutResult put = idempotencyStore.putIfAbsent(idempotencyKey, null);
        if (put.isReplay()) {
            String existingId = (String) put.value();
            if (existingId != null) {
                return new SurveyResponse(existingId, req.rating(), req.resolved(), "感谢您的反馈！（幂等回放）");
            }
        }

        // 评分范围校验
        if (req.rating() < 1 || req.rating() > 5) {
            throw new IllegalArgumentException(
                    "Rating must be between 1 and 5, got: " + req.rating());
        }

        String surveyId = "SRV-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        var record = new SatisfactionSurveyPort.SurveyRecord(
                surveyId,
                req.tenantId(),
                req.userId(),
                req.conversationId(),
                req.rating(),
                req.feedback(),
                req.resolved(),
                System.currentTimeMillis());
        repo.save(record);

        // 回填幂等结果
        idempotencyStore.replace(idempotencyKey, surveyId);
        return new SurveyResponse(surveyId, req.rating(), req.resolved(), "感谢您的反馈！");
    }

    @ToolSpec(
            name = "list_surveys_by_conversation",
            description = "查询会话关联的满意度调查记录，返回surveyId/rating/feedback/resolved。适用于：用户问'这个会话的满意度评分是多少'、'之前评价过吗'。只读工具。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public List<SatisfactionSurveyPort.SurveyRecord> listByConversation(String conversationId) {
        return repo.findByConversation(conversationId);
    }

    public record SurveyRequest(
            String tenantId,
            String userId,
            String conversationId,
            int rating,
            String feedback,
            boolean resolved
    ) {}

    public record SurveyResponse(
            String surveyId,
            int rating,
            boolean resolved,
            String message
    ) {}
}
