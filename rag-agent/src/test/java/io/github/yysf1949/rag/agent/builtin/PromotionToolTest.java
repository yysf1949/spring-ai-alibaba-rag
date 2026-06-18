package io.github.yysf1949.rag.agent.builtin;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PromotionToolTest {

    private final PromotionTool tool = new PromotionTool();

    @Test
    void queryProductPromotionsForFlashSaleProduct() {
        var resp = tool.queryProductPromotions(new PromotionTool.ProductPromotionsRequest(
                "tenant-1", "user-1", "SKU-FLASH-001"));
        assertThat(resp.promotions()).hasSize(1); // 仅秒杀（618 不包含此商品）
        assertThat(resp.promotions()).extracting("name").contains("限时秒杀");
    }

    @Test
    void queryProductPromotionsForElectronics() {
        var resp = tool.queryProductPromotions(new PromotionTool.ProductPromotionsRequest(
                "tenant-1", "user-1", "SKU-ELEC-001"));
        assertThat(resp.promotions()).hasSize(1); // 仅 618
        assertThat(resp.promotions()).extracting("name").contains("618 年中大促");
    }

    @Test
    void queryProductPromotionsForNonExistentProduct() {
        var resp = tool.queryProductPromotions(new PromotionTool.ProductPromotionsRequest(
                "tenant-1", "user-1", "SKU-UNKNOWN"));
        assertThat(resp.promotions()).isEmpty();
    }

    @Test
    void queryAllActivePromotionsReturnsAllThree() {
        var resp = tool.queryAllActive(new PromotionTool.AllPromotionsRequest("tenant-1", "user-1"));
        assertThat(resp.promotions()).hasSize(3);
        assertThat(resp.promotions()).extracting("name")
                .containsExactlyInAnyOrder("618 年中大促", "限时秒杀", "满减特惠");
    }

    @Test
    void queryAllActivePromotionsHasCorrectDiscountTypes() {
        var resp = tool.queryAllActive(new PromotionTool.AllPromotionsRequest("tenant-1", "user-1"));
        assertThat(resp.promotions()).extracting("discountType")
                .containsExactlyInAnyOrder("PERCENT", "FIXED", "THRESHOLD");
    }
}
