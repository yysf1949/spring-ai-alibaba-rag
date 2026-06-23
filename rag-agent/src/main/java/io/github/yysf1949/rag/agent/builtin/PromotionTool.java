package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 促销活动查询工具 — 纯读工具，L1 只读。
 *
 * <h2>场景</h2>
 * <ul>
 *   <li>{@code query_product_promotions} — "这个商品有特价吗？"（查询某商品参与的促销）</li>
 *   <li>{@code query_all_active_promotions} — "现在有什么活动？"（查询所有进行中的促销）</li>
 * </ul>
 *
 * <h2>实现说明</h2>
 * <p>促销数据在真实系统中来自营销中台（Promotion Center）。本阶段使用 mock 数据
 * （类似 {@code LogisticsTool} 模式），后续可通过 Port 接口扩展为真实 API 调用。</p>
 */
@Component
public class PromotionTool {

    /** 预设促销列表（mock 数据）。 */
    private static final List<Promotion> PROMOTIONS = List.of(
            // 618 年中大促 — 指定商品 8 折
            new Promotion("PROMO-618", "618 年中大促", "SPECIFIC", "PERCENT", 20.0,
                    "2026-06-01T00:00:00Z", "2026-06-20T23:59:59Z",
                    List.of("SKU-ELEC-001", "SKU-ELEC-002", "SKU-BOOK-001")),
            // 限时秒杀 — 指定商品直降 50 元
            new Promotion("PROMO-FLASH", "限时秒杀", "SPECIFIC", "FIXED", 50.0,
                    "2026-06-18T10:00:00Z", "2026-06-18T23:59:59Z",
                    List.of("SKU-FLASH-001")),
            // 满减特惠 — 全店满 300 减 50（THRESHOLD 类型，不在产品级查询中返回）
            new Promotion("PROMO-REDBAG", "满减特惠", "ALL", "THRESHOLD", 50.0,
                    "2026-06-15T00:00:00Z", "2026-06-25T23:59:59Z",
                    List.of(), 300.0)
    );

    // ==================== L1 查询 ====================

    @ToolSpec(
            name = "query_product_promotions",
            description = "查询商品参与的促销，返回promotions(id/name/discountType/discountValue/起止时间)。适用于：用户问'这个商品有活动吗'、'现在买划算吗'。只读。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public ProductPromotionsResponse queryProductPromotions(ProductPromotionsRequest req) {
        var applicable = PROMOTIONS.stream()
                .filter(p -> p.isProductInPromotion(req.productId()))
                .map(p -> new PromotionBrief(
                        p.id, p.name, p.discountType, p.discountValue,
                        p.startTime, p.endTime))
                .toList();
        return new ProductPromotionsResponse(req.productId(), applicable);
    }

    @ToolSpec(
            name = "query_all_active_promotions",
            description = "查询全部进行中促销，返回promotions列表(id/name/discountType/discountValue/起止时间)。适用于：用户问'现在有什么活动'、'有没有优惠'。只读。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public AllPromotionsResponse queryAllActive(AllPromotionsRequest req) {
        var list = PROMOTIONS.stream()
                .map(p -> new PromotionBrief(
                        p.id, p.name, p.discountType, p.discountValue,
                        p.startTime, p.endTime))
                .toList();
        return new AllPromotionsResponse(list);
    }

    // ==================== Records ====================

    public record ProductPromotionsRequest(String tenantId, String userId, String productId) { }
    public record ProductPromotionsResponse(String productId, List<PromotionBrief> promotions) { }

    public record AllPromotionsRequest(String tenantId, String userId) { }
    public record AllPromotionsResponse(List<PromotionBrief> promotions) { }

    public record PromotionBrief(
            String id, String name, String discountType, double discountValue,
            String startTime, String endTime) { }

    /** 内部促销模型。 */
    private record Promotion(
            String id, String name, String scope, String discountType,
            double discountValue, String startTime, String endTime,
            List<String> productIds, double threshold) {

        Promotion(String id, String name, String scope, String discountType,
                  double discountValue, String startTime, String endTime,
                  List<String> productIds) {
            this(id, name, scope, discountType, discountValue, startTime, endTime, productIds, 0.0);
        }

        boolean isProductInPromotion(String productId) {
            // 满减特惠 (THRESHOLD 类型) 对所有商品生效，但属于"全店活动"，不在产品级查询中返回
            if ("THRESHOLD".equals(discountType)) return false;
            if ("ALL".equals(scope)) return true;
            return productIds.contains(productId);
        }
    }
}
