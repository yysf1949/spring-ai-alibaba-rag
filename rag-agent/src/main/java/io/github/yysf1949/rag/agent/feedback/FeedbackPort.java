package io.github.yysf1949.rag.agent.feedback;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * 用户反馈仓库端口 — Phase 40 T1 (R10: Active Learning 反馈闭环第一步).
 *
 * <h2>设计意图</h2>
 * <p>收集用户对 Agent 回复的反馈 (👍/👎 + 文字 + 1-5 评分)，作为后续微调
 * pipeline 的训练数据源。遵循六边形架构：上游业务通过此 Port 持久化反馈，
 * 存储实现可换 (InMemory / H2 / Redis)。</p>
 *
 * <h2>字段最小集</h2>
 * <ul>
 *   <li>{@code feedbackId} — 反馈唯一 ID（{@code FB-xxxx}）</li>
 *   <li>{@code tenantId} — 租户 ID（硬隔离，跨租户不可查）</li>
 *   <li>{@code userId} — 终端用户 ID</li>
 *   <li>{@code conversationId} — 会话 ID</li>
 *   <li>{@code messageId} — 关联 Agent 消息 ID（可空，给 👍/👎 用）</li>
 *   <li>{@code thumb} — 👍 / 👎 / null (纯文字反馈)</li>
 *   <li>{@code rating} — 1-5 评分（可空）</li>
 *   <li>{@code comment} — 文字反馈（可空）</li>
 *   <li>{@code sourceChannel} — 来源渠道 (web / wechat / email / api)</li>
 *   <li>{@code kbVersion} — 关联 KB 版本（可空，便于按 KB 评估）</li>
 *   <li>{@code createdAt} — 创建时间戳 (epoch millis)</li>
 * </ul>
 *
 * <h2>升级路径</h2>
 * <p>生产可换 MySQL/PostgreSQL + Flyway 迁移。本 Phase 实现 InMemory + H2 + Redis。
 * JSONL 导出 (Phase 40 T2) 走 {@link #findByTenantRange} 流式输出。</p>
 */
public interface FeedbackPort {

    /**
     * 保存反馈记录。
     *
     * @param record 反馈记录
     * @return 保存后的记录
     */
    FeedbackRecord save(FeedbackRecord record);

    /**
     * 根据反馈 ID 查询。
     *
     * @param tenantId 租户 ID（硬隔离）
     * @param feedbackId 反馈 ID
     */
    Optional<FeedbackRecord> findById(String tenantId, String feedbackId);

    /**
     * 查询某会话的全部反馈（按时间升序）。
     */
    List<FeedbackRecord> findByConversation(String tenantId, String conversationId);

    /**
     * 查询某租户的全部反馈（分页）。T2 导出时流式调用此方法。
     *
     * @param tenantId 租户 ID
     * @param limit 最多返回条数 (建议 100-500)
     */
    List<FeedbackRecord> findByTenant(String tenantId, int limit);

    /**
     * 统计某租户的总反馈数。
     */
    long countByTenant(String tenantId);

    /**
     * Phase 40 T2: 按租户 + 时间范围查询反馈（用于 JSONL 导出）。
     *
     * <p>{@code fromMs} / {@code toMs} 为 epoch millis；任一为 {@code null} 表示
     * 该端无界。返回按 {@code createdAt} 升序排列，便于增量训练数据按时间排序。</p>
     *
     * <p>实现要求：
     * <ul>
     *   <li>真实流式行为 — 建议 H2 用 {@code JdbcTemplate.queryForStream}，
     *       Redis 用 {@code ZRANGEBYSCORE} + 游标式 HGETALL，
     *       InMemory 按已排序 List 顺序逐条 yield</li>
     *   <li>{@code limit} 是软上限，超过即停 — 不要为了 limit 多查一条</li>
     * </ul></p>
     *
     * @param tenantId 租户 ID（硬隔离）
     * @param fromMs 时间下界 (epoch millis, 含)，{@code null} = 无下界
     * @param toMs   时间上界 (epoch millis, 含)，{@code null} = 无上界
     * @param limit  最多返回条数 (建议 1000-10000)
     */
    List<FeedbackRecord> findByTenantRange(String tenantId, Long fromMs, Long toMs, int limit);

    /**
     * 反馈持久化记录。
     */
    record FeedbackRecord(
            String feedbackId,
            String tenantId,
            String userId,
            String conversationId,
            String messageId,
            Thumb thumb,
            Integer rating,
            String comment,
            String sourceChannel,
            String kbVersion,
            long createdAt
    ) {
        public FeedbackRecord {
            if (feedbackId == null || feedbackId.isBlank()) {
                throw new IllegalArgumentException("feedbackId required");
            }
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException("tenantId required");
            }
            if (rating != null && (rating < 1 || rating > 5)) {
                throw new IllegalArgumentException("rating must be 1-5, got: " + rating);
            }
        }

        public static String newFeedbackId() {
            return "FB-" + java.util.UUID.randomUUID().toString().substring(0, 8);
        }

        public static FeedbackRecord of(
                String tenantId, String userId, String conversationId,
                Thumb thumb, Integer rating, String comment
        ) {
            return new FeedbackRecord(
                    newFeedbackId(), tenantId, userId, conversationId,
                    null, thumb, rating, comment, "api", null,
                    Instant.now().toEpochMilli()
            );
        }
    }

    /**
     * 反馈方向：UP (👍) / DOWN (👎) / null (纯文字或评分)。
     */
    enum Thumb {
        UP, DOWN;

        public static Thumb of(Boolean up) {
            if (up == null) return null;
            return up ? UP : DOWN;
        }
    }
}