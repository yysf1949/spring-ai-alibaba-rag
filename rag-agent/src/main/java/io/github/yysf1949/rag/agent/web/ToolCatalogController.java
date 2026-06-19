package io.github.yysf1949.rag.agent.web;

import io.github.yysf1949.rag.agent.action.RiskLevel;
import io.github.yysf1949.rag.agent.action.ToolDescriptor;
import io.github.yysf1949.rag.agent.action.ToolRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工具目录 REST 接口 — 返回所有已注册 {@code @ToolSpec} 工具的元数据。
 *
 * <p>Swagger UI 的 /api/tools 端点让运维 / 开发快速浏览 Agent 可用工具、
 * 风险等级、幂等属性，无需翻代码。</p>
 */
@RestController
@RequestMapping("/api/tools")
@Tag(name = "Tool Catalog", description = "Agent 已注册工具目录 — 名称、描述、风险等级。")
@ConditionalOnBean(ToolRegistry.class)
public class ToolCatalogController {

    private final ToolRegistry toolRegistry;

    public ToolCatalogController(ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    /**
     * 工具摘要 DTO。
     */
    public record ToolSummary(
            String name,
            String description,
            RiskLevel riskLevel,
            boolean idempotent,
            boolean requiresIdempotencyKey,
            Long maxAmountCents,
            boolean requiresConfirmationToken
    ) { }

    @GetMapping
    @Operation(
            summary = "列出所有已注册 Agent 工具",
            description = "返回 ToolRegistry 中所有 @ToolSpec 工具的名称、描述、风险等级、"
                    + "幂等属性。用于 Swagger UI 浏览和运维排查。")
    public List<ToolSummary> listTools() {
        List<String> names = toolRegistry.listNames();
        return names.stream()
                .map(name -> {
                    ToolDescriptor d = toolRegistry.get(name);
                    return new ToolSummary(
                            d.name(),
                            d.description(),
                            d.riskLevel(),
                            d.idempotent(),
                            d.requiresIdempotencyKey(),
                            d.maxAmountCents(),
                            d.requiresConfirmationToken());
                })
                .toList();
    }
}
