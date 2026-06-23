package io.github.yysf1949.rag.llm.provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

/**
 * Phase 38: Provider 注册表 — 集中管理所有已注册的 LLM Provider。
 *
 * <h2>职责</h2>
 * <ul>
 *   <li>收集所有 {@link LlmProvider} Bean (通过 Spring {@link ObjectProvider})</li>
 *   <li>提供按 providerId / displayName 查询</li>
 *   <li>提供可用 provider 列表 (isAvailable = true)</li>
 *   <li>提供按成本排序的 provider 列表</li>
 * </ul>
 */
@Component
public class ProviderRegistry {

    private static final Logger log = LoggerFactory.getLogger(ProviderRegistry.class);

    private final ConcurrentMap<String, LlmProvider> providers = new ConcurrentHashMap<>();

    public ProviderRegistry(ObjectProvider<LlmProvider> providerProvider) {
        providerProvider.forEach(provider -> {
            providers.put(provider.providerId(), provider);
            log.info("Registered LLM provider: {} (model={}, available={})",
                    provider.providerId(), provider.defaultModel(), provider.isAvailable());
        });
    }

    /** 获取所有已注册的 provider */
    public List<LlmProvider> listAll() {
        return new ArrayList<>(providers.values());
    }

    /** 获取可用的 provider (isAvailable = true) */
    public List<LlmProvider> listAvailable() {
        return providers.values().stream()
                .filter(LlmProvider::isAvailable)
                .collect(Collectors.toList());
    }

    /** 按 providerId 获取 */
    public Optional<LlmProvider> get(String providerId) {
        return Optional.ofNullable(providers.get(providerId));
    }

    /** 按 providerId 列表批量获取 */
    public List<LlmProvider> get(List<String> providerIds) {
        return providerIds.stream()
                .map(providers::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /** 获取所有 providerId */
    public List<String> listIds() {
        return new ArrayList<>(providers.keySet());
    }

    /** 按成本从低到高排序的可用 provider 列表 */
    public List<LlmProvider> sortByCostAsc() {
        return listAvailable().stream()
                .sorted(Comparator.comparingDouble(LlmProvider::inputPricePerKTokens)
                        .thenComparingDouble(LlmProvider::outputPricePerKTokens))
                .collect(Collectors.toList());
    }

    /** 获取 provider 数量 */
    public int count() {
        return providers.size();
    }

    /** 获取可用 provider 数量 */
    public int countAvailable() {
        return (int) providers.values().stream().filter(LlmProvider::isAvailable).count();
    }

    /** 健康检查: 返回每个 provider 的状态 */
    public Map<String, ProviderHealthStatus> healthCheck() {
        Map<String, ProviderHealthStatus> status = new HashMap<>();
        for (LlmProvider p : providers.values()) {
            status.put(p.providerId(), new ProviderHealthStatus(
                    p.displayName(),
                    p.defaultModel(),
                    p.isAvailable(),
                    p.inputPricePerKTokens(),
                    p.outputPricePerKTokens()
            ));
        }
        return status;
    }

    public record ProviderHealthStatus(
            String displayName,
            String defaultModel,
            boolean available,
            double inputPricePerKTokens,
            double outputPricePerKTokens
    ) {}
}
