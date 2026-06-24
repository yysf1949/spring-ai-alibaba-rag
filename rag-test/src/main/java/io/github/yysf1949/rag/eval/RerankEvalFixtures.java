package io.github.yysf1949.rag.eval;

import io.github.yysf1949.rag.core.model.Chunk;
import io.github.yysf1949.rag.core.model.ChunkStatus;
import io.github.yysf1949.rag.core.model.EmbeddingChannel;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Mock rerank 评测数据集 — 10 queries × 10 candidates.
 *
 * <p>每条 query 有 10 个候选 chunk, 预定义 ground truth relevance (0-4).
 * 数据覆盖中英文查询、语义匹配、关键词匹配、混合场景。</p>
 *
 * <h2>模拟模型打分逻辑</h2>
 * <p>不同模型对同一 (query, chunk) 对的打分不同, 反映真实模型差异:</p>
 * <ul>
 *   <li><b>BGE</b>: 语义匹配强, 关键词匹配中等. score = 0.7*semantic + 0.3*keyword</li>
 *   <li><b>BCE</b>: 关键词匹配强, 语义匹配中等. score = 0.4*semantic + 0.6*keyword</li>
 *   <li><b>Cohere</b>: 英文语义最强, 中文弱. 英文: 0.8*semantic + 0.2*keyword; 中文: 0.3*semantic + 0.1*keyword</li>
 * </ul>
 */
public final class RerankEvalFixtures {

    private RerankEvalFixtures() {}

    /**
     * 生成默认 10 条评测数据.
     */
    public static List<RerankEvalFixture> defaultFixtures() {
        List<RerankEvalFixture> fixtures = new ArrayList<>();

        // Q1: 中文退货政策
        fixtures.add(buildFixture(
                "退货政策是什么",
                new String[][]{
                        {"c1-1", "我们的退货政策是7天无理由退货，商品需保持原包装完整。"},
                        {"c1-2", "退款将在收到退货后3-5个工作日内原路返回。"},
                        {"c1-3", "特价商品不支持退货，仅支持换货。"},
                        {"c1-4", "物流配送时间为2-5个工作日。"},  // 不相关
                        {"c1-5", "会员积分可以在商城抵扣现金。"},  // 不相关
                        {"c1-6", "退货流程：在订单页面点击申请退货，填写原因并上传照片。"},
                        {"c1-7", "电子产品保修期为一年。"},  // 弱相关
                        {"c1-8", "跨境订单不支持7天无理由退货。"},
                        {"c1-9", "客服工作时间为9:00-22:00。"},  // 不相关
                        {"c1-10", "价保政策：购买后7天内降价可申请差价补偿。"}  // 弱相关
                },
                new int[]{4, 3, 3, 0, 0, 4, 1, 2, 0, 1}
        ));

        // Q2: English refund process
        fixtures.add(buildFixture(
                "How do I get a refund?",
                new String[][]{
                        {"c2-1", "To request a refund, go to your order history and click 'Request Refund'."},
                        {"c2-2", "Refunds are processed within 3-5 business days to your original payment method."},
                        {"c2-3", "退货政策是7天无理由退货。"},  // 中文, 弱相关
                        {"c2-4", "Shipping costs are non-refundable for partial returns."},
                        {"c2-5", "You can track your order status in the app."},
                        {"c2-6", "If your refund is delayed, contact customer service."},
                        {"c2-7", "Membership benefits include free shipping and priority support."},
                        {"c2-8", "Cancelled orders receive automatic refunds within 24 hours."},
                        {"c2-9", "The return window for electronics is 15 days."},
                        {"c2-10", "Gift cards cannot be refunded once purchased."}
                },
                new int[]{4, 4, 1, 3, 0, 3, 0, 4, 2, 1}
        ));

        // Q3: 中文物流查询
        fixtures.add(buildFixture(
                "怎么查物流",
                new String[][]{
                        {"c3-1", "您可以在订单详情页面查看物流信息，包括快递公司和运单号。"},
                        {"c3-2", "物流配送时间为2-5个工作日。"},
                        {"c3-3", "退货政策是7天无理由退货。"},  // 不相关
                        {"c3-4", "支持顺丰、圆通、中通等主流快递。"},
                        {"c3-5", "跨境物流通常需要7-15个工作日。"},
                        {"c3-6", "您可以通过运单号在快递官网查询实时物流。"},
                        {"c3-7", "会员可享受免费配送服务。"},
                        {"c3-8", "退货流程：在订单页面点击申请退货。"},  // 弱相关
                        {"c3-9", "客服可以帮您查询物流状态。"},
                        {"c3-10", "价保政策：购买后7天内降价可申请差价补偿。"}  // 不相关
                },
                new int[]{4, 3, 0, 3, 2, 4, 2, 1, 3, 0}
        ));

        // Q4: English product warranty
        fixtures.add(buildFixture(
                "What is the warranty period?",
                new String[][]{
                        {"c4-1", "Electronics come with a 1-year warranty from the date of purchase."},
                        {"c4-2", "Warranty does not cover physical damage or water damage."},
                        {"c4-3", "To claim warranty, bring the product and receipt to any service center."},
                        {"c4-4", "退货政策是7天无理由退货。"},  // 不相关
                        {"c4-5", "Extended warranty is available for purchase within 30 days."},
                        {"c4-6", "Refunds are processed within 3-5 business days."},  // 弱相关
                        {"c4-7", "The return window for electronics is 15 days."},
                        {"c4-8", "Membership benefits include free shipping."},  // 不相关
                        {"c4-9", "Warranty service is free for manufacturing defects."},
                        {"c4-10", "Cancelled orders receive automatic refunds."}  // 不相关
                },
                new int[]{4, 3, 4, 0, 3, 1, 2, 0, 4, 0}
        ));

        // Q5: 中文会员权益
        fixtures.add(buildFixture(
                "会员有什么权益",
                new String[][]{
                        {"c5-1", "会员权益包括免费配送、专属折扣、优先客服和生日礼遇。"},
                        {"c5-2", "黄金会员可享受全场9折优惠。"},
                        {"c5-3", "会员积分可以在商城抵扣现金，100积分=1元。"},
                        {"c5-4", "退货政策是7天无理由退货。"},  // 不相关
                        {"c5-5", "白金会员每月可获得3张免邮券。"},
                        {"c5-6", "会员等级根据年度消费金额自动升级。"},
                        {"c5-7", "物流配送时间为2-5个工作日。"},  // 不相关
                        {"c5-8", "会员可优先参与限时秒杀活动。"},
                        {"c5-9", "客服工作时间为9:00-22:00。"},  // 弱相关
                        {"c5-10", "价保政策：购买后7天内降价可申请差价补偿。"}  // 弱相关
                },
                new int[]{4, 4, 3, 0, 3, 3, 0, 4, 1, 1}
        ));

        // Q6: English coupon usage
        fixtures.add(buildFixture(
                "How to use coupons?",
                new String[][]{
                        {"c6-1", "To use a coupon, enter the coupon code at checkout and click 'Apply'."},
                        {"c6-2", "Coupons cannot be combined with other promotions unless stated."},
                        {"c6-3", "Expired coupons cannot be used or extended."},
                        {"c6-4", "退货政策是7天无理由退货。"},  // 不相关
                        {"c6-5", "You can view your available coupons in 'My Account > Coupons'."},
                        {"c6-6", "Refunds are processed within 3-5 business days."},  // 不相关
                        {"c6-7", "Some coupons have minimum purchase requirements."},
                        {"c6-8", "优惠券可以在结算时使用。"},  // 中文, 弱相关
                        {"c6-9", "Coupons are automatically applied if 'auto-apply' is enabled."},
                        {"c6-10", "Membership benefits include exclusive coupon access."}
                },
                new int[]{4, 3, 3, 0, 4, 0, 3, 1, 3, 2}
        ));

        // Q7: 中文支付方式
        fixtures.add(buildFixture(
                "支持哪些支付方式",
                new String[][]{
                        {"c7-1", "我们支持微信支付、支付宝、银行卡和信用卡等多种支付方式。"},
                        {"c7-2", "花呗分期支持3期、6期、12期免息。"},
                        {"c7-3", "跨境订单支持PayPal和国际信用卡支付。"},
                        {"c7-4", "退货政策是7天无理由退货。"},  // 不相关
                        {"c7-5", "货到付款仅限部分城市。"},
                        {"c7-6", "支付成功后您将收到短信通知。"},
                        {"c7-7", "物流配送时间为2-5个工作日。"},  // 不相关
                        {"c7-8", "会员可使用积分抵扣部分金额。"},
                        {"c7-9", "客服可以协助处理支付问题。"},
                        {"c7-10", "价保政策：购买后7天内降价可申请差价补偿。"}  // 不相关
                },
                new int[]{4, 3, 3, 0, 2, 2, 0, 3, 2, 0}
        ));

        // Q8: English order cancellation
        fixtures.add(buildFixture(
                "How to cancel an order?",
                new String[][]{
                        {"c8-1", "To cancel an order, go to 'My Orders' and click 'Cancel' before shipping."},
                        {"c8-2", "Orders that have been shipped cannot be cancelled but can be returned."},
                        {"c8-3", "Cancelled orders receive automatic refunds within 24 hours."},
                        {"c8-4", "退货政策是7天无理由退货。"},  // 弱相关
                        {"c8-5", "You can modify your shipping address before the order is shipped."},
                        {"c8-6", "Partial cancellation is supported for multi-item orders."},
                        {"c8-7", "Refunds are processed within 3-5 business days."},
                        {"c8-8", "Membership benefits include free shipping."},  // 不相关
                        {"c8-9", "If cancellation fails, contact customer service immediately."},
                        {"c8-10", "The return window for electronics is 15 days."}  // 弱相关
                },
                new int[]{4, 3, 4, 1, 2, 3, 2, 0, 3, 1}
        ));

        // Q9: 中文发票
        fixtures.add(buildFixture(
                "怎么开发票",
                new String[][]{
                        {"c9-1", "您可以在下单时选择开具电子发票，填写抬头和税号信息。"},
                        {"c9-2", "电子发票将在订单完成后24小时内发送到您的邮箱。"},
                        {"c9-3", "增值税专用发票需联系客服开具。"},
                        {"c9-4", "退货政策是7天无理由退货。"},  // 不相关
                        {"c9-5", "发票内容默认为商品明细，可修改为办公用品。"},
                        {"c9-6", "物流配送时间为2-5个工作日。"},  // 不相关
                        {"c9-7", "会员积分可以在商城抵扣现金。"},  // 不相关
                        {"c9-8", "纸质发票需提供邮寄地址，运费到付。"},
                        {"c9-9", "客服可以协助处理发票问题。"},
                        {"c9-10", "价保政策：购买后7天内降价可申请差价补偿。"}  // 不相关
                },
                new int[]{4, 4, 3, 0, 3, 0, 0, 2, 2, 0}
        ));

        // Q10: English account security
        fixtures.add(buildFixture(
                "How to secure my account?",
                new String[][]{
                        {"c10-1", "Enable two-factor authentication in 'Account Settings > Security'."},
                        {"c10-2", "Use a strong password with at least 12 characters, mixing letters, numbers, and symbols."},
                        {"c10-3", "Never share your password with anyone, including customer service."},
                        {"c10-4", "退货政策是7天无理由退货。"},  // 不相关
                        {"c10-5", "Review your login history regularly for suspicious activity."},
                        {"c10-6", "Refunds are processed within 3-5 business days."},  // 不相关
                        {"c10-7", "Change your password immediately if you suspect a breach."},
                        {"c10-8", "We will never ask for your password via email or phone."},
                        {"c10-9", "Enable login notifications to get alerts on new device logins."},
                        {"c10-10", "Membership benefits include free shipping."}  // 不相关
                },
                new int[]{4, 4, 4, 0, 3, 0, 4, 4, 3, 0}
        ));

        return fixtures;
    }

    // --- Simulated scoring ---

    /**
     * 模拟某模型对 (query, document) 对的 rerank 分数.
     *
     * <p>基于 ground truth relevance 加入模型特性偏移, 使不同模型产生不同排序:</p>
     * <ul>
     *   <li>BGE: 中英双语均衡, 直接用 relevance 作基础分</li>
     *   <li>BCE: 关键词偏重, 对高关键词重叠的文档加分</li>
     *   <li>Cohere: 英文语义最强, 中文弱 — 中文文档 relevance 衰减</li>
     * </ul>
     *
     * <p>注意: 为了确定性测试, 不使用随机噪声. 分数由 relevance + 模型偏移决定.</p>
     */
    public static double simulatedScore(String model, String query, String document, int groundTruthRelevance) {
        boolean queryIsChinese = query.chars().anyMatch(c -> c > 0x4E00 && c < 0x9FFF);
        boolean docIsChinese = document.chars().anyMatch(c -> c > 0x4E00 && c < 0x9FFF);
        double keywordOverlap = computeKeywordOverlap(query, document);

        // Base score from ground truth relevance (0-4 scale → 0.0-1.0)
        double base = groundTruthRelevance / 4.0;

        // Model-specific perturbation that changes the ranking order
        return switch (model) {
            case "BGE" -> {
                // BGE: balanced, reliable across languages
                // But slightly less precise than Cohere on English — occasionally swaps adjacent relevance
                if (!queryIsChinese && groundTruthRelevance == 3) {
                    // BGE sometimes under-scores "relevant" English docs relative to Cohere
                    yield 0.5 + 0.05 * keywordOverlap;
                }
                double langBonus = (queryIsChinese == docIsChinese) ? 0.1 : -0.1;
                yield base + langBonus + 0.05 * keywordOverlap;
            }
            case "BCE" -> {
                // BCE: keyword-heavy — when keyword overlap is low, relevance signal is weakened
                // This causes BCE to sometimes rank keyword-matching but irrelevant docs higher
                if (keywordOverlap > 0.3 && groundTruthRelevance <= 1) {
                    // BCE false positive: keyword match but irrelevant
                    yield 0.5 + 0.3 * keywordOverlap;
                }
                yield base * 0.8 + 0.2 * keywordOverlap;
            }
            case "Cohere" -> {
                if (queryIsChinese) {
                    // Cohere on Chinese: severe degradation — loses relevance signal
                    // High-relevance Chinese docs get mixed with low-relevance ones
                    if (groundTruthRelevance >= 3) {
                        yield 0.3 + 0.1 * keywordOverlap; // under-scored
                    } else {
                        yield 0.25 + 0.15 * keywordOverlap; // over-scored relative to relevant
                    }
                } else {
                    // English: Cohere is excellent — amplifies relevance differences
                    if (groundTruthRelevance >= 3) {
                        yield base * 1.2 + 0.1 * keywordOverlap;
                    } else {
                        yield base * 0.5;
                    }
                }
            }
            default -> base;
        };
    }

    /**
     * @deprecated use {@link #simulatedScore(String, String, String, int)}
     */
    @Deprecated
    public static double simulatedScore(String model, String query, String document) {
        return simulatedScore(model, query, document, 0);
    }

    private static double computeKeywordOverlap(String query, String document) {
        // Simple token overlap
        String[] qTokens = query.toLowerCase().split("\\s+");
        String docLower = document.toLowerCase();
        int matches = 0;
        for (String token : qTokens) {
            if (token.length() > 1 && docLower.contains(token)) {
                matches++;
            }
        }
        return Math.min(1.0, (double) matches / Math.max(1, qTokens.length));
    }

    private static double computeSemanticSim(String query, String document) {
        // Mock semantic similarity: check if document is about the same topic
        // For Chinese queries, check if document contains Chinese text about the same topic
        boolean queryIsChinese = query.chars().anyMatch(c -> c > 0x4E00 && c < 0x9FFF);
        boolean docIsChinese = document.chars().anyMatch(c -> c > 0x4E00 && c < 0x9FFF);

        // Same language bonus
        double base = (queryIsChinese == docIsChinese) ? 0.6 : 0.2;

        // Keyword overlap contributes to semantic similarity too
        base += 0.3 * computeKeywordOverlap(query, document);

        return Math.min(1.0, base);
    }

    private static double noise(double amplitude) {
        return (Math.random() - 0.5) * 2 * amplitude;
    }

    // --- Fixture builder ---

    private static RerankEvalFixture buildFixture(String query, String[][] candidates, int[] relevance) {
        List<Chunk> chunks = new ArrayList<>();
        Map<String, Integer> relMap = new HashMap<>();

        for (int i = 0; i < candidates.length; i++) {
            String chunkId = candidates[i][0];
            String content = candidates[i][1];
            chunks.add(makeChunk(chunkId, content));
            relMap.put(chunkId, relevance[i]);
        }

        return new RerankEvalFixture(query, chunks, relMap);
    }

    private static Chunk makeChunk(String chunkId, String content) {
        return new Chunk(
                chunkId,
                "t1",           // tenantId
                "kb1",          // kbId
                "doc-" + chunkId, // documentId
                "v1",           // documentVersion
                "title",        // title
                "section",      // sectionPath
                content,        // content
                Set.of(),       // permissionTags
                ChunkStatus.ACTIVE,
                Instant.now(),  // publishedAt
                "uri",          // sourceUri
                new float[0],   // embedding
                EmbeddingChannel.STUB_HASH
        );
    }
}
