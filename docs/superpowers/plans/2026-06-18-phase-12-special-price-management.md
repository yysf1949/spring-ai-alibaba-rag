# Phase 12 — 电商特价管理业务工具

> 在 Phase 9-11 已完成的 Action Layer + 业务工具 + 多存储后端之上，补充**价保申请**和**促销查询**两个场景，覆盖"客户问价/比价/降价索赔"全链路。

## 设计原则

1. **对齐已有模式** — 每个工具 = `@Component` + `@ToolSpec` 方法 + Port 接口 + InMemory 存储
2. **风险分级严格执行** — L1 只读/ L3 写操作（金额门控）
3. **幂等约束** — 写操作 `requiresIdempotencyKey=true` + L3 `maxAmountCents`
4. **PostgreSQL/Redis 不作为此阶段要求** — Phase 11 的 Port 模式已支持多存储；Phase 12 仅实现 InMemory，未来按需扩展

## 新增工具概览

| 工具 | 方法 | 风险 | 说明 |
|---|---|---|---|
| **PriceProtectionTool** | `query_price_protection_policy` | L1_READ | 查询某商品价保政策（天数和比例） |
| | `check_price_protection_eligibility` | L1_READ | 查询某订单是否符合价保条件 |
| | `apply_price_protection` | L3_BUSINESS_STATE | 申请价保退差价（200元上限，超限转人工） |
| **PromotionTool** | `query_product_promotions` | L1_READ | 查询某商品当前参与的促销活动 |
| | `query_all_active_promotions` | L1_READ | 查询所有进行中的促销活动（列表） |

## Task 划分

### Task 1 — PriceProtectionPort + InMemory 存储

**文件**: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/port/PriceProtectionPort.java`
```java
package io.github.yysf1949.rag.agent.builtin.port;

import java.util.Optional;

public interface PriceProtectionPort {
    PriceProtectionRecord save(PriceProtectionRecord record);
    Optional<PriceProtectionRecord> findByIdAndTenant(String claimId, String tenantId);
    /** 查询某商品当前价保政策 */
    PriceProtectionPolicy getPolicy(String productCategory);
    /** 查询某订单是否还在价保期内 */
    boolean isWithinProtectionPeriod(String orderTimeStr, String productCategory);

    record PriceProtectionRecord(
            String claimId, String tenantId, String userId, String orderId,
            String productId, long refundAmountCents, long originalPriceCents,
            long currentPriceCents, String status, String reason) {
        public static PriceProtectionRecord pending(String claimId, String tenantId, String userId,
                                                    String orderId, String productId,
                                                    long refundAmountCents, long originalPriceCents,
                                                    long currentPriceCents, String reason) {
            return new PriceProtectionRecord(claimId, tenantId, userId, orderId, productId,
                    refundAmountCents, originalPriceCents, currentPriceCents, "PENDING", reason);
        }
    }
    record PriceProtectionPolicy(int protectionDays, double maxRefundRatio) {}
}
```

**文件**: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/store/InMemoryPriceProtectionRepository.java`
- HashMap+AtomicLong ID 生成
- `getPolicy`: 统一返回 7天 / 100% 价保
- `isWithinProtectionPeriod`: 简单判断订单时间至今 ≤7 天

### Task 2 — PriceProtectionTool + 测试

**文件**: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/PriceProtectionTool.java`
- 3 个方法

`query_price_protection_policy`: L1
```java
public QueryPolicyResponse queryPolicy(QueryPolicyRequest req) {
    var policy = repo.getPolicy(req.productCategory());
    return new QueryPolicyResponse(policy.protectionDays(), policy.maxRefundRatio());
}
```
Request: `(String tenantId, String userId, String productCategory)`
Response: `(int protectionDays, double maxRefundRatio)`

`check_price_protection_eligibility`: L1
```java
public EligibilityResponse checkEligibility(EligibilityRequest req) {
    var eligible = repo.isWithinProtectionPeriod(req.orderTime(), req.productCategory());
    return new EligibilityResponse(req.orderId(), eligible,
            eligible ? "在价保期内，可申请价保" : "已超过价保期限");
}
```
Request: `(String tenantId, String userId, String orderId, String orderTime, String productCategory)`
Response: `(String orderId, boolean eligible, String message)`

`apply_price_protection`: L3 (金额门控 200 元)
```java
public ApplyResponse applyPriceProtection(ApplyRequest req) {
    // 1. 金额门控
    if (req.refundAmountCents() > MAX_AMOUNT_CENTS) throw AmountLimitExceededException;
    // 2. 查价保政策
    var policy = repo.getPolicy(req.productCategory());
    // 3. 价保比例检查
    long maxRefund = (long)(req.originalPriceCents() * policy.maxRefundRatio());
    if (req.refundAmountCents() > maxRefund) throw ...;
    // 4. 保存申请
    var claim = PriceProtectionRecord.pending(...);
    repo.save(claim);
    return new ApplyResponse(claim.claimId(), "PENDING", claim.refundAmountCents());
}
```
Request: `(String tenantId, String userId, String orderId, String productId, long refundAmountCents, long originalPriceCents, long currentPriceCents, String orderTime, String productCategory, String reason, String idempotencyKey)`
Response: `(String claimId, String status, long refundAmountCents)`

**测试** `PriceProtectionToolTest.java`:
- `queryPolicy returns default`
- `checkEligibility within period → true`
- `checkEligibility expired → false`
- `applyPriceProtection happy path → PENDING`
- `applyPriceProtection exceeds amount gate → AmountLimitExceeded`
- `applyPriceProtection idempotent on same key → same result`

### Task 3 — PromotionTool + 测试（mock 数据）

**文件**: `rag-agent/src/main/java/io/github/yysf1949/rag/agent/builtin/PromotionTool.java`
- 纯读工具，不写数据，无需 Port（类似 LogisticsTool 模式）
- mock 数据：3 个预设促销活动

`query_product_promotions`: L1
```java
public ProductPromotionsResponse queryProductPromotions(ProductPromotionsRequest req) {
    var applicable = PROMOTIONS.stream()
            .filter(p -> p.isProductInPromotion(req.productId()))
            .map(p -> new PromotionBrief(p.id(), p.name(), p.discountType(), p.discountValue(),
                    p.startTime(), p.endTime()))
            .toList();
    return new ProductPromotionsResponse(req.productId(), applicable);
}
```

`query_all_active_promotions`: L1
```java
public AllPromotionsResponse queryAllActive(AllPromotionsRequest req) {
    var list = PROMOTIONS.stream()
            .map(p -> new PromotionBrief(...))
            .toList();
    return new AllPromotionsResponse(list);
}
```

Mock 数据: 3 个促销
1. "618年中大促" — 全场 8 折，适用所有商品
2. "限时秒杀" — 商品 ID "SKU-FLASH-001" 直降 50 元
3. "满减特惠" — 满 300 减 50

**测试** `PromotionToolTest.java`:
- `queryProductPromotions for specific product → 1 or 2 matches`
- `queryProductPromotions for non-existent product → empty list`
- `queryAllActivePromotions returns all 3`

### Task 4 — 编译验证 + 测试回归 + 文档 sync + push

- `mvn -pl rag-agent test`: 确保新增 ~15 tests 通过，不破坏已有 135 tests
- `mvn test`: 全仓库回归 ~389 tests
- 更新 `docs/evolution.md` → Phase 12 条目
- 双写 Obsidian
- `git push origin feature/agent-action-layer`
- 汇报总测试数

## 风险/边界

1. **价保金额门控 200 元** — 跟 CouponTool 的 ISSUE_MAX_AMOUNT_CENTS 一致
2. **PromotionTool 纯 mock** — 促销数据在真实系统中来自营销中台；本阶段不做对接
3. **不建 H2/MySQL/Redis 存储** — 价保申请数量少，InMemory 足够演示；Phase 11 已展示扩展方式

## 测试增量估算
| **测试** | 96 → ~150 (新增 +54) |
+| **实际测试** | **148** (Phase 9:39 + Phase 10:57 + Phase 11:39 + Phase 12:13) / 0 回归 |