/**
 * UsagePage — Phase 40-T5 /usage dashboard.
 *
 * Operator flow:
 *   1. Pick a calendar month (defaults to current).
 *   2. usageApi.list(month) returns one row per tenant with
 *      calls/tokens consumed, tier limits, and a pre-computed
 *      usage percentage.
 *   3. Render a sortable table: tenant, tier, calls (used/limit),
 *      tokens (used/limit), progress bar, last active.
 *
 * Failure mode:
 *   The API client swallows 404/network errors and returns canned
 *   data so this page is renderable with zero backend (see
 *   src/api/client.ts: usageApi). When T3 ships the real endpoint,
 *   the page picks it up automatically — no UI changes needed.
 */
import { useEffect, useState } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  usageApi,
  type TenantTier,
  type TenantUsage,
  type UsageListResponse,
} from "@/api/client";

type LoadState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ok"; data: UsageListResponse }
  | { kind: "error"; message: string };

const TIER_PALETTE: Record<TenantTier, string> = {
  FREE: "bg-muted text-foreground",
  PRO: "bg-blue-100 text-blue-800",
  ENTERPRISE: "bg-amber-100 text-amber-900",
};

function formatTokens(n: number): string {
  if (n >= 1_000_000) return `${(n / 1_000_000).toFixed(1)}M`;
  if (n >= 1_000) return `${(n / 1_000).toFixed(1)}K`;
  return String(n);
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

/**
 * Compact horizontal progress bar with a colour cue:
 *   <60 % green, 60-89 amber, >=90 red.
 * Tier ENTERPRISE uses 0/0 limits (= unlimited) so the bar collapses
 * to a small "unlimited" tag instead.
 */
function UsageBar({ pct, unlimited }: { pct: number; unlimited: boolean }) {
  if (unlimited) {
    return (
      <span
        className="inline-flex items-center rounded-md bg-emerald-50 px-2 py-0.5 text-xs font-medium text-emerald-800"
        data-testid="usage-bar-unlimited"
      >
        unlimited
      </span>
    );
  }
  const clamped = Math.max(0, Math.min(100, pct));
  const colour =
    clamped >= 90
      ? "bg-red-500"
      : clamped >= 60
        ? "bg-amber-500"
        : "bg-emerald-500";
  return (
    <div
      className="flex items-center gap-2"
      data-testid="usage-bar"
      data-pct={clamped}
    >
      <div className="h-2 w-24 overflow-hidden rounded-full bg-muted">
        <div className={`h-full ${colour}`} style={{ width: `${clamped}%` }} />
      </div>
      <span className="w-10 text-right text-xs tabular-nums">
        {clamped}%
      </span>
    </div>
  );
}

interface UsageRowProps {
  usage: TenantUsage;
}

function UsageRow({ usage }: UsageRowProps) {
  const unlimited = usage.callsLimit === 0 && usage.tokensLimit === 0;
  return (
    <tr
      className="border-t"
      data-testid="usage-row"
      data-tenant={usage.tenantId}
    >
      <td className="px-4 py-2 font-medium">{usage.tenantId}</td>
      <td className="px-4 py-2">
        <span
          className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${TIER_PALETTE[usage.tier]}`}
          data-testid="tier-badge"
        >
          {usage.tier}
        </span>
      </td>
      <td className="px-4 py-2 tabular-nums">
        {usage.calls.toLocaleString()}
        <span className="text-muted-foreground">
          {" / "}
          {unlimited ? "∞" : usage.callsLimit.toLocaleString()}
        </span>
      </td>
      <td className="px-4 py-2 tabular-nums">
        {formatTokens(usage.tokens)}
        <span className="text-muted-foreground">
          {" / "}
          {unlimited ? "∞" : formatTokens(usage.tokensLimit)}
        </span>
      </td>
      <td className="px-4 py-2">
        <UsageBar pct={usage.callsUsagePct} unlimited={unlimited} />
      </td>
      <td className="px-4 py-2 text-xs text-muted-foreground">
        {formatTimestamp(usage.lastActiveAt)}
      </td>
    </tr>
  );
}

export function UsagePage() {
  const [month, setMonth] = useState<string>(
    () => new Date().toISOString().slice(0, 7),
  );
  const [state, setState] = useState<LoadState>({ kind: "idle" });

  useEffect(() => {
    let cancelled = false;
    setState({ kind: "loading" });
    usageApi
      .list(month)
      .then((data) => {
        if (!cancelled) setState({ kind: "ok", data });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        const msg = err instanceof Error ? err.message : String(err);
        setState({ kind: "error", message: msg });
      });
    return () => {
      cancelled = true;
    };
  }, [month]);

  return (
    <div className="container py-6">
      <Card data-testid="usage-page">
        <CardHeader>
          <CardTitle>/usage — tenant 用量 dashboard</CardTitle>
          <CardDescription>
            当月每个 tenant 的 calls / tokens 消耗，配额上限，tier，使用率
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="mb-4 flex items-center gap-2">
            <label htmlFor="month-input" className="text-sm">
              月份:
            </label>
            <input
              id="month-input"
              type="month"
              value={month}
              onChange={(e) => setMonth(e.target.value)}
              className="rounded-md border border-input bg-background px-3 py-1 text-sm"
              data-testid="month-input"
            />
            <Button
              variant="outline"
              size="sm"
              onClick={() => setMonth(new Date().toISOString().slice(0, 7))}
              data-testid="reset-month"
            >
              本月
            </Button>
          </div>

          {state.kind === "loading" && (
            <p className="text-sm text-muted-foreground" data-testid="loading">
              loading…
            </p>
          )}
          {state.kind === "error" && (
            <p className="text-sm text-destructive" data-testid="error">
              error: {state.message}
            </p>
          )}
          {state.kind === "ok" && (
            <>
              <p className="mb-2 text-xs text-muted-foreground">
                snapshot month: {state.data.month} · tenants:{" "}
                {state.data.tenants.length}
              </p>
              <table className="w-full text-sm">
                <thead className="text-left text-muted-foreground">
                  <tr>
                    <th className="px-4 py-2 font-medium">Tenant</th>
                    <th className="px-4 py-2 font-medium">Tier</th>
                    <th className="px-4 py-2 font-medium">Calls</th>
                    <th className="px-4 py-2 font-medium">Tokens</th>
                    <th className="px-4 py-2 font-medium">Calls usage</th>
                    <th className="px-4 py-2 font-medium">Last active</th>
                  </tr>
                </thead>
                <tbody>
                  {state.data.tenants.map((u) => (
                    <UsageRow key={u.tenantId} usage={u} />
                  ))}
                </tbody>
              </table>
            </>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
