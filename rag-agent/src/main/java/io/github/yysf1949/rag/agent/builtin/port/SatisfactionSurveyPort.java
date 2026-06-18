package io.github.yysf1949.rag.agent.builtin.port;

import java.util.List;
import java.util.Optional;

/**
 * 满意度调查仓库端口 — 定义存储契约。
 *
 * <h2>设计意图</h2>
 * <p>会话结束时收集用户满意度评分和反馈，用于服务质量监控和持续改进。
 * 遵循六边形架构：Tool 通过此 Port 与存储交互，不依赖具体实现。</p>
 *
 * <h2>升级路径</h2>
 * <p>生产可换 MySQL/Postgres + Flyway 迁移。本 Phase 范围使用 InMemory 实现。</p>
 */
public interface SatisfactionSurveyPort {

    /**
     * 保存满意度调查记录。
     *
     * @param record 调查记录
     * @return 保存后的记录
     */
    SurveyRecord save(SurveyRecord record);

    /**
     * 根据会话 ID 查询调查记录。
     *
     * @param conversationId 会话 ID
     * @return 匹配的调查记录列表
     */
    List<SurveyRecord> findByConversation(String conversationId);

    /**
     * 满意度调查持久化记录。
     *
     * @param surveyId       调查 ID（主键）
     * @param tenantId       租户 ID
     * @param userId         用户 ID
     * @param conversationId 会话 ID
     * @param rating         评分（1-5）
     * @param feedback       文字反馈（可为空）
     * @param resolved       问题是否已解决
     * @param createdAt      创建时间戳（毫秒）
     */
    record SurveyRecord(
            String surveyId,
            String tenantId,
            String userId,
            String conversationId,
            int rating,
            String feedback,
            boolean resolved,
            long createdAt
    ) {}
}
