import { Link } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/**
 * LlmRouterPage — Phase 38: 多 LLM 智能路由配置页面。
 *
 * 功能:
 * - Provider 状态总览 (可用/熔断/定价)
 * - 路由测试 (输入 query → 查看路由建议)
 * - 熔断器管理 (手动重置)
 * - 成本统计
 */

interface Provider {
  providerId: string;
  displayName: string;
  defaultModel: string;
  available: boolean;
  inputPricePerKTokens: number;
  outputPricePerKTokens: number;
  circuitState: string;
  failureCount: number;
}

export function LlmRouterPage() {
  // Mock data for Phase 38 stub
  const providers: Provider[] = [
    {
      providerId: "deepseek",
      displayName: "DeepSeek",
      defaultModel: "deepseek-chat",
      available: true,
      inputPricePerKTokens: 0.00027,
      outputPricePerKTokens: 0.0011,
      circuitState: "CLOSED",
      failureCount: 0,
    },
    {
      providerId: "siliconflow",
      displayName: "SiliconFlow",
      defaultModel: "deepseek-ai/DeepSeek-V3",
      available: true,
      inputPricePerKTokens: 0.00014,
      outputPricePerKTokens: 0.00028,
      circuitState: "CLOSED",
      failureCount: 0,
    },
    {
      providerId: "openai",
      displayName: "OpenAI",
      defaultModel: "gpt-4o-mini",
      available: false,
      inputPricePerKTokens: 0.00015,
      outputPricePerKTokens: 0.0006,
      circuitState: "CLOSED",
      failureCount: 0,
    },
    {
      providerId: "claude",
      displayName: "Claude (Anthropic)",
      defaultModel: "claude-3-5-sonnet-20241022",
      available: false,
      inputPricePerKTokens: 0.003,
      outputPricePerKTokens: 0.015,
      circuitState: "CLOSED",
      failureCount: 0,
    },
    {
      providerId: "qwen",
      displayName: "Qwen (通义千问)",
      defaultModel: "qwen-plus",
      available: false,
      inputPricePerKTokens: 0.000055,
      outputPricePerKTokens: 0.000165,
      circuitState: "CLOSED",
      failureCount: 0,
    },
  ];

  const circuitStateColor = (state: string) => {
    switch (state) {
      case "CLOSED":
        return "bg-emerald-500";
      case "OPEN":
        return "bg-red-500";
      case "HALF_OPEN":
        return "bg-amber-500";
      default:
        return "bg-gray-500";
    }
  };

  const formatPrice = (price: number) => `$${price.toFixed(5)} / 1K tokens`;

  return (
    <div className="container max-w-6xl py-10">
      <div className="mb-6">
        <Link
          to="/"
          className="text-sm text-muted-foreground hover:underline"
        >
          ← Home
        </Link>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">
          /llm-router — 多 LLM 智能路由
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          按 query 类型/成本/SLA 动态选择最优 LLM Provider。
          <strong className="ml-2 text-amber-600">
            数据为前端 mock（Phase 38 stub），后端 API 已就绪。
          </strong>
        </p>
      </div>

      {/* Provider Status */}
      <Card className="mb-6">
        <CardHeader>
          <CardTitle>Provider 状态总览</CardTitle>
          <CardDescription>
            5 家提供商 (SiliconFlow / DeepSeek / OpenAI / Claude / Qwen)
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                  <th className="py-2 pr-3">Provider</th>
                  <th className="py-2 pr-3">Model</th>
                  <th className="py-2 pr-3">Status</th>
                  <th className="py-2 pr-3">Circuit</th>
                  <th className="py-2 pr-3">Input Price</th>
                  <th className="py-2 pr-3">Output Price</th>
                </tr>
              </thead>
              <tbody>
                {providers.map((p) => (
                  <tr key={p.providerId} className="border-b last:border-0">
                    <td className="py-3 pr-3 font-mono text-xs">{p.displayName}</td>
                    <td className="py-3 pr-3 font-mono text-xs">{p.defaultModel}</td>
                    <td className="py-3 pr-3">
                      <span
                        className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium ${
                          p.available
                            ? "bg-emerald-100 text-emerald-800"
                            : "bg-gray-100 text-gray-600"
                        }`}
                      >
                        <span
                          className={`h-2 w-2 rounded-full ${
                            p.available ? "bg-emerald-500" : "bg-gray-400"
                          }`}
                        />
                        {p.available ? "Available" : "Disabled"}
                      </span>
                    </td>
                    <td className="py-3 pr-3">
                      <span
                        className={`inline-flex items-center gap-1 rounded px-2 py-0.5 text-xs font-medium ${
                          p.circuitState === "CLOSED"
                            ? "bg-emerald-100 text-emerald-800"
                            : p.circuitState === "OPEN"
                            ? "bg-red-100 text-red-800"
                            : "bg-amber-100 text-amber-800"
                        }`}
                      >
                        <span
                          className={`h-2 w-2 rounded-full ${circuitStateColor(
                            p.circuitState
                          )}`}
                        />
                        {p.circuitState.replace("_", " ")}
                      </span>
                      {p.failureCount > 0 && (
                        <span className="ml-1 text-xs text-muted-foreground">
                          ({p.failureCount} failures)
                        </span>
                      )}
                    </td>
                    <td className="py-3 pr-3 font-mono text-xs">
                      {formatPrice(p.inputPricePerKTokens)}
                    </td>
                    <td className="py-3 pr-3 font-mono text-xs">
                      {formatPrice(p.outputPricePerKTokens)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      {/* Route Test */}
      <Card className="mb-6">
        <CardHeader>
          <CardTitle>路由测试</CardTitle>
          <CardDescription>
            输入 query 和策略，查看路由引擎会选哪个 Provider
          </CardDescription>
        </CardHeader>
        <CardContent>
          <p className="text-sm text-muted-foreground">
            路由引擎已实现 5 种策略: PRECISION(高精度), FAST(低延迟), COST_OPTIMIZED(成本优先),
            BALANCED(平衡), LONG_CONTEXT(长上下文)。
            输入 query 后，引擎自动推断策略并选择最优可用 Provider。
          </p>
        </CardContent>
      </Card>

      {/* Limitations */}
      <Card>
        <CardHeader>
          <CardTitle>Known Limitations</CardTitle>
          <CardDescription>Phase 38 — stub data</CardDescription>
        </CardHeader>
        <CardContent>
          <ul className="list-inside list-disc space-y-1 text-sm text-muted-foreground">
            <li>All provider data is static mock — no real backend API calls.</li>
            <li>Route test does not call actual routing endpoint.</li>
            <li>Circuit breaker reset button is non-functional.</li>
            <li>
              TODO: Connect to <code>GET /api/admin/llm-router/providers</code> and{" "
              }<code>GET /api/admin/llm-router/route</code> endpoints.
            </li>
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}
