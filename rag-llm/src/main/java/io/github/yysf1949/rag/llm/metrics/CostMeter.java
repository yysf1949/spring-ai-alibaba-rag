package io.github.yysf1949.rag.llm.metrics;

import io.github.yysf1949.rag.llm.provider.LlmProvider;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Phase 38: LLM 成本监控 — Micrometer 指标暴露。
 *
 * <h2>暴露的指标</h2>
 * <ul>
 *   <li>{@code llm.cost.cents} — 每次调用的成本 (美元 cent), 带 provider/model 标签</li>
 *   <li>{@code llm.tokens.input} — 输入 token 数</li>
 *   <li>{@code llm.tokens.output} — 输出 token 数</li>
 *   <li>{@code llm.tokens.total} — 总 token 数</li>
 *   <li>{@code llm.cost.total.cents} — 累计总成本 (gauge)</li>
 * </ul>
 */
@Component
public class CostMeter {

    private static final Logger log = LoggerFactory.getLogger(CostMeter.class);

    private final MeterRegistry meterRegistry;

    private final ConcurrentMap<String, Counter> costCounters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> tokenInputSummaries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, DistributionSummary> tokenOutputSummaries = new ConcurrentHashMap<>();

    // 累计成本追踪 (用于 gauge)
    private final ConcurrentMap<String, java.util.concurrent.atomic.AtomicLong> totalCostByProvider = new ConcurrentHashMap<>();

    public CostMeter(ObjectProvider<MeterRegistry> meterRegistryProvider) {
        this.meterRegistry = meterRegistryProvider.getIfAvailable();
    }

    /**
     * 记录一次 LLM 调用的成本和 token 使用情况。
     *
     * @param providerId 提供商 ID
     * @param modelName 模型名
     * @param inputTokens 输入 token 数
     * @param outputTokens 输出 token 数
     * @param costCents 实际成本 (美元 cent)
     */
    public void recordCall(String providerId, String modelName, int inputTokens, int outputTokens, double costCents) {
        if (meterRegistry == null) return;

        List<Tag> tags = List.of(
                Tag.of("provider", providerId),
                Tag.of("model", modelName)
        );

        // 记录成本
        Counter costCounter = costCounters.computeIfAbsent(
                "cost:" + providerId + ":" + modelName,
                k -> Counter.builder("llm.cost.cents")
                        .description("LLM API cost per call in US cents")
                        .tags(tags)
                        .register(meterRegistry)
        );
        costCounter.increment(costCents / 100.0); // 存储为美元

        // 累计成本
        totalCostByProvider.computeIfAbsent(providerId, p -> new java.util.concurrent.atomic.AtomicLong(0))
                .addAndGet((long) costCents);

        // 记录输入 token
        DistributionSummary inputSummary = tokenInputSummaries.computeIfAbsent(
                "input:" + providerId + ":" + modelName,
                k -> DistributionSummary.builder("llm.tokens.input")
                        .description("LLM input tokens per call")
                        .tags(tags)
                        .register(meterRegistry)
        );
        inputSummary.record(inputTokens);

        // 记录输出 token
        DistributionSummary outputSummary = tokenOutputSummaries.computeIfAbsent(
                "output:" + providerId + ":" + modelName,
                k -> DistributionSummary.builder("llm.tokens.output")
                        .description("LLM output tokens per call")
                        .tags(tags)
                        .register(meterRegistry)
        );
        outputSummary.record(outputTokens);
    }

    /**
     * 根据 provider 的定价计算预估成本。
     */
    public double estimateCost(LlmProvider provider, int inputTokens, int outputTokens) {
        return provider.estimateCost(inputTokens, outputTokens) * 100; // 转换为 cents
    }

    /**
     * 获取各 provider 的累计总成本 (美元)
     */
    public java.util.Map<String, Double> getTotalCostByProvider() {
        java.util.Map<String, Double> result = new java.util.HashMap<>();
        for (java.util.Map.Entry<String, java.util.concurrent.atomic.AtomicLong> e : totalCostByProvider.entrySet()) {
            result.put(e.getKey(), e.getValue().get() / 100.0); // 转换为美元
        }
        return result;
    }

    /**
     * 重置累计成本 (用于测试或手动清零)
     */
    public void resetTotalCost(String providerId) {
        totalCostByProvider.remove(providerId);
    }

    public void resetAllTotalCost() {
        totalCostByProvider.clear();
    }
}
