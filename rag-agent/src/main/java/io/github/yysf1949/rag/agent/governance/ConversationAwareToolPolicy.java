package io.github.yysf1949.rag.agent.governance;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.github.yysf1949.rag.agent.builtin.port.SatisfactionSurveyPort;
import io.github.yysf1949.rag.agent.exception.ToolNotFoundException;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 会话感知的工具选择策略 — 根据对话轮次和用户意图动态调整工具可用范围。
 *
 * <h2>规则</h2>
 * <ul>
 *   <li>对话前 3 轮 (messageCount ≤ 6): 仅 L1 查询工具 (用户还在描述问题)</li>
 *   <li>对话 4-10 轮 (messageCount 7-20): L1 + L2 (开始创建工单/发券)</li>
 *   <li>对话 &gt;10 轮 (messageCount &gt; 20): 全部工具 (复杂问题需要完整能力)</li>
 *   <li>用户意图 QUERY: 始终只开放 L1</li>
 *   <li>用户意图 COMPLAINT: 自动开放 create_complaint + create_reminder_ticket</li>
 *   <li>历史满意度低 (≤2 分): 自动升级一档</li>
 * </ul>
 */
@Component
public class ConversationAwareToolPolicy implements ToolSelectionPolicy {

    /** COMPLAINT 意图下自动开放的工具 */
    private static final Set<String> COMPLAINT_TOOLS = Set.of(
            "create_complaint", "create_reminder_ticket"
    );

    private final SatisfactionSurveyPort satisfactionSurvey;
    private final ToolRegistry registry;

    public ConversationAwareToolPolicy(SatisfactionSurveyPort satisfactionSurvey,
                                       ToolRegistry registry) {
        this.satisfactionSurvey = Objects.requireNonNull(satisfactionSurvey);
        this.registry = Objects.requireNonNull(registry);
    }

    @Override
    public List<String> filterTools(ToolSelectionContext ctx, List<String> candidateTools) {
        if (ctx == null || candidateTools == null) return List.of();

        // 确定允许的最高风险级别
        RiskLevel maxAllowed = determineMaxRiskLevel(ctx);

        List<String> result = new ArrayList<>();

        // 第一步: 按风险级别过滤
        for (String name : candidateTools) {
            RiskLevel risk = toolRiskLevel(name);
            if (risk.compareTo(maxAllowed) <= 0) {
                result.add(name);
            }
        }

        // 第二步: 意图特殊处理 — COMPLAINT 自动开放投诉工具
        if (ctx.userIntent() == ToolSelectionPolicy.UserIntent.COMPLAINT) {
            for (String name : candidateTools) {
                if (COMPLAINT_TOOLS.contains(name) && !result.contains(name)) {
                    result.add(name);
                }
            }
        }

        return result;
    }

    /**
     * 根据对话轮次、用户意图和满意度确定允许的最高风险级别。
     */
    private RiskLevel determineMaxRiskLevel(ToolSelectionContext ctx) {
        // QUERY 意图: 始终只开放 L1
        if (ctx.userIntent() == ToolSelectionPolicy.UserIntent.QUERY) {
            return RiskLevel.L1_READ;
        }

        // 检查历史满意度: 低满意度用户 (≤2) 自动升级
        boolean lowSatisfaction = hasLowSatisfaction(ctx);

        int messageCount = ctx.messageCount();

        if (lowSatisfaction) {
            // 低满意度自动升级一档
            if (messageCount <= 6) {
                return RiskLevel.L2_REVERSIBLE;  // 本来 L1 → 升级到 L2
            }
            return RiskLevel.L3_BUSINESS_STATE;  // 本来 L2 或全开 → 全开
        }

        // 正常分级
        if (messageCount <= 6) {
            return RiskLevel.L1_READ;            // 前 3 轮: 只有查询
        } else if (messageCount <= 20) {
            return RiskLevel.L2_REVERSIBLE;      // 4-10 轮: 查询 + 可逆操作
        } else {
            return RiskLevel.L3_BUSINESS_STATE;  // >10 轮: 全部工具
        }
    }

    /**
     * 查询用户历史满意度评分。评分 ≤ 2 视为低满意度。
     */
    private boolean hasLowSatisfaction(ToolSelectionContext ctx) {
        if (ctx.conversationId() == null || ctx.userId() == null) {
            return false;
        }
        try {
            List<SatisfactionSurveyPort.SurveyRecord> records =
                    satisfactionSurvey.findByConversation(ctx.conversationId());
            if (records.isEmpty()) {
                return false;
            }
            double avgRating = records.stream()
                    .mapToInt(SatisfactionSurveyPort.SurveyRecord::rating)
                    .average()
                    .orElse(5.0);
            return avgRating <= 2.0;
        } catch (Exception e) {
            return false;
        }
    }

    private RiskLevel toolRiskLevel(String toolName) {
        try {
            return registry.get(toolName).riskLevel();
        } catch (ToolNotFoundException e) {
            return RiskLevel.L4_HIGH_RISK;
        }
    }
}
