package io.github.yysf1949.rag.agent.builtin;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolSpec;
import io.github.yysf1949.rag.agent.builtin.port.InventoryPort;
import org.springframework.stereotype.Component;

/**
 * 商品库存查询工具 — L1 只读。
 *
 * <h2>对齐文章"查询 ≠ 执行"</h2>
 * <p>库存查询是售后决策的前置条件：换货前先查有没有货，补发前先查库存。
 * 严格只读，不修改库存数据（扣减库存属于订单系统职责）。</p>
 */
@Component
public class InventoryTool {

    private final InventoryPort repo;

    public InventoryTool(InventoryPort repo) {
        this.repo = repo;
    }

    @ToolSpec(
            name = "check_stock",
            description = "查询商品库存信息：库存数量、是否在售、价格(分)、品类。"
                    + "适用于：判断商品是否有货、是否可换货、查价格。只读工具，不修改库存数据。",
            riskLevel = RiskLevel.L1_READ,
            idempotent = true,
            requiresIdempotencyKey = false
    )
    public StockResponse checkStock(StockRequest req) {
        return repo.findStock(req.tenantId(), req.productId())
                .map(s -> new StockResponse(
                        s.productId(), s.productName(), s.availableQuantity(),
                        s.onSale(), s.priceCents(), s.category(), null))
                .orElse(new StockResponse(
                        req.productId(), "UNKNOWN", 0, false, 0, null, "商品不存在"));
    }

    public record StockRequest(String tenantId, String productId) {}

    public record StockResponse(
            String productId,
            String productName,
            int availableQuantity,
            boolean onSale,
            long priceCents,
            String category,
            String error
    ) {}
}
