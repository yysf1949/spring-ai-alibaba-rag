import { Link } from "react-router-dom";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";

/**
 * RateLimitPage — Phase 36-T3 Rate Limit stub UI.
 *
 * Displays mock tenant rate limit data. No real backend metrics
 * endpoint exists — this is a frontend const array stub.
 *
 * TODO: Phase 34+ JWT auth lands → replace stub with real
 * backend metrics endpoint (e.g. GET /api/admin/rate-limits).
 */

interface TenantRateLimit {
  tenantId: string;
  /** Requests per minute limit. */
  limitPerMin: number;
  /** Current usage count in this window. */
  currentUsage: number;
  /** Seconds until the window resets. */
  resetInSec: number;
}

/**
 * Mock rate limit data — 3 tenants for the dev environment.
 *
 * Phase 36-T3: these are hard-coded constants. No real metrics
 * endpoint exists in the backend.
 */
const MOCK_RATE_LIMITS: TenantRateLimit[] = [
  { tenantId: "dev", limitPerMin: 100, currentUsage: 23, resetInSec: 42 },
  { tenantId: "staging", limitPerMin: 500, currentUsage: 147, resetInSec: 15 },
  { tenantId: "prod", limitPerMin: 10000, currentUsage: 5231, resetInSec: 8 },
];

function usagePercent(current: number, limit: number): number {
  if (limit <= 0) return 0;
  return Math.round((current / limit) * 100);
}

function usageBarColor(pct: number): string {
  if (pct >= 90) return "bg-red-500";
  if (pct >= 70) return "bg-amber-500";
  return "bg-emerald-500";
}

function usageTextColor(pct: number): string {
  if (pct >= 90) return "text-red-600";
  if (pct >= 70) return "text-amber-600";
  return "text-emerald-600";
}

export function RateLimitPage() {
  return (
    <div className="container max-w-4xl py-10">
      <div className="mb-6">
        <Link
          to="/"
          className="text-sm text-muted-foreground hover:underline"
        >
          ← Home
        </Link>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">
          /rate-limit — Rate Limit 状态
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          查看当前 tenant 限流状态。{" "}
          <strong>数据为前端 mock（Phase 36-T3 stub），无真实后端 metrics endpoint。</strong>
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>Tenant rate limit overview</CardTitle>
          <CardDescription>
            Current usage window for each configured tenant. Data is static
            mock — the backend has no{" "}
            <code className="rounded bg-muted px-1.5 py-0.5">
              GET /api/admin/metrics
            </code>{" "}
            endpoint (Phase 34+ scope).
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="overflow-x-auto">
            <table className="w-full text-sm">
              <thead>
                <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                  <th className="py-2 pr-3">tenantId</th>
                  <th className="py-2 pr-3">limit/min</th>
                  <th className="py-2 pr-3">current usage</th>
                  <th className="py-2 pr-3">usage %</th>
                  <th className="py-2 pr-3">resetIn (s)</th>
                </tr>
              </thead>
              <tbody>
                {MOCK_RATE_LIMITS.map((t) => {
                  const pct = usagePercent(t.currentUsage, t.limitPerMin);
                  return (
                    <tr
                      key={t.tenantId}
                      className="border-b last:border-0"
                    >
                      <td className="py-3 pr-3 font-mono text-xs">
                        {t.tenantId}
                      </td>
                      <td className="py-3 pr-3 font-mono">
                        {t.limitPerMin.toLocaleString()}
                      </td>
                      <td className="py-3 pr-3 font-mono">
                        {t.currentUsage.toLocaleString()}
                      </td>
                      <td className="py-3 pr-3">
                        <div className="flex items-center gap-2">
                          <div className="h-2 w-24 overflow-hidden rounded-full bg-muted">
                            <div
                              className={`h-full rounded-full ${usageBarColor(pct)}`}
                              style={{ width: `${Math.min(pct, 100)}%` }}
                            />
                          </div>
                          <span
                            className={`text-xs font-medium ${usageTextColor(pct)}`}
                          >
                            {pct}%
                          </span>
                        </div>
                      </td>
                      <td className="py-3 pr-3 font-mono text-xs">
                        {t.resetInSec}s
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Known limitations</CardTitle>
          <CardDescription>
            Phase 36-T3 — no real backend metrics.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ul className="list-inside list-disc space-y-1 text-sm text-muted-foreground">
            <li>
              All rate limit data is static mock (frontend const array).
            </li>
            <li>
              No JWT auth (Phase 34 scope). This page is publicly
              accessible in the dev environment.
            </li>
            <li>
              TODO: Phase 34+ replace stub with{" "}
              <code className="rounded bg-muted px-1.5 py-0.5">
                GET /api/admin/rate-limits
              </code>{" "}
              endpoint. Also consider adding a per-tenant reset button
              for operators.
            </li>
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}
