package io.github.yysf1949.rag.pipeline.rewrite;

import java.util.Map;

/**
 * Default Chinese-language synonym table used by {@link RuleBasedQueryRewriter}
 * when no tenant-specific vocabulary is configured.
 *
 * <p>The set below is intentionally small — a starter vocabulary covering
 * the article's example scenarios (refund, shipping, orders). Operators
 * extend per-tenant via {@link SynonymTable.Builder} or by replacing this
 * bean entirely with a YAML-loaded table.</p>
 *
 * <h2>Coverage</h2>
 * <ul>
 *   <li><b>退款 / 退货</b> — covers the article's "退款规则" demo flow</li>
 *   <li><b>运费 / 邮费</b> — covers shipping-cost questions</li>
 *   <li><b>订单</b> — order status / tracking</li>
 * </ul>
 *
 * <p>Adding more entries is a pure-data change — no code review needed
 * beyond "does this synonym make the embedding recall better?".</p>
 */
public final class DefaultChineseSynonymTable {

    private DefaultChineseSynonymTable() {}

    /** Built-in starter table. Stable across all tenants unless overridden. */
    public static SynonymTable create() {
        return SynonymTable.builder()
                // 退款 / 退货 — the article's headline scenario
                .add("退款", "退钱", "退回", "退款申请", "退货", "退掉", "退款流程")
                .add("运费", "邮费", "快递费", "运费险")
                // 订单 / 物流
                .add("订单", "单号", "订单号")
                .add("物流", "快递", "配送", "发货", "到哪了")
                // 售后 / 客服
                .add("售后", "客服", "投诉", "维权")
                .add("发票", "电子发票", "增值税发票", "专票", "普票")
                .build();
    }

    /**
     * Convenience: expose as a raw {@code Map<String, List<String>>} for
     * callers that want to merge into their own table builder without
     * copying field-by-field.
     */
    public static Map<String, java.util.List<String>> rawEntries() {
        return create().canonicals().stream().collect(java.util.stream.Collectors.toMap(
                c -> c,
                c -> java.util.List.copyOf(create().surfacesOf(c))));
    }
}
