import { useCallback, useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import {
  versionsApi,
  type KbVersionMeta,
  type KbVersionStatus,
} from "@/api/client";

/**
 * GradualPage — Phase 36-T3 灰度发布 UI.
 *
 * Wire flow:
 *   1. On mount, call versionsApi.listVersions("default") to fetch every
 *      KbVersionMeta for the dev KB.
 *   2. Render a table: versionId | status | docCount | createdAt | action.
 *   3. "设为活跃" button → versionsApi.activateVersion(versionId)
 *      → reload the version list.
 *   4. "回滚" button → versionsApi.rollbackVersion(versionId)
 *      → reload the version list.
 *
 * Known constraints (Phase 36-T3):
 *   - Backend does NOT expose POST /api/agent/kb-versions/{id}/activate
 *     or rollback endpoints yet. The client stubs simulate success so
 *     the UI renders the full happy path (load → click → refresh).
 *   - TODO: Phase 34+ replace stub endpoints with real backend calls.
 *   - No JWT auth (Phase 34 scope). The dev tenant "dev" flows through
 *     via the axios interceptor-free path (MdcTenantFilter fallback).
 */

type LoadState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ok"; versions: KbVersionMeta[] }
  | { kind: "error"; message: string };

type ActionState =
  | { kind: "idle" }
  | { kind: "acting"; versionId: number; action: "activate" | "rollback" }
  | { kind: "done"; message: string }
  | { kind: "error"; message: string };

function formatTimestamp(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

function StatusBadge({ status }: { status: KbVersionStatus }) {
  const palette: Record<KbVersionStatus, string> = {
    DRAFT: "bg-muted text-foreground",
    STAGING: "bg-blue-100 text-blue-800",
    ACTIVE: "bg-emerald-100 text-emerald-900",
    DEPRECATED: "bg-amber-100 text-amber-900",
  };
  return (
    <span
      className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${palette[status]}`}
    >
      {status}
    </span>
  );
}

export function GradualPage() {
  const [loadState, setLoadState] = useState<LoadState>({ kind: "idle" });
  const [actionState, setActionState] = useState<ActionState>({ kind: "idle" });
  const [kbId, setKbId] = useState("default");
  const [draftKbId, setDraftKbId] = useState("default");

  const fetchVersions = useCallback(async (id: string) => {
    setLoadState({ kind: "loading" });
    try {
      const resp = await versionsApi.listVersions(id);
      setLoadState({ kind: "ok", versions: resp.versions });
    } catch (err) {
      setLoadState({
        kind: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }, []);

  useEffect(() => {
    if (loadState.kind === "idle") {
      void fetchVersions(kbId);
    }
  }, [loadState.kind, kbId, fetchVersions]);

  const handleKbSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = draftKbId.trim();
    if (!trimmed) return;
    setKbId(trimmed);
    setLoadState({ kind: "idle" });
  };

  const handleReload = () => {
    setLoadState({ kind: "idle" });
  };

  const handleActivate = async (versionId: number) => {
    setActionState({ kind: "acting", versionId, action: "activate" });
    try {
      await versionsApi.activateVersion(versionId);
      setActionState({ kind: "done", message: `v${versionId} set as active` });
      // Reload the list to reflect the state change.
      setLoadState({ kind: "idle" });
    } catch (err) {
      setActionState({
        kind: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  };

  const handleRollback = async (versionId: number) => {
    setActionState({ kind: "acting", versionId, action: "rollback" });
    try {
      await versionsApi.rollbackVersion(versionId);
      setActionState({
        kind: "done",
        message: `Rolled back to v${versionId}`,
      });
      // Reload the list to reflect the state change.
      setLoadState({ kind: "idle" });
    } catch (err) {
      setActionState({
        kind: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  };

  const versions = loadState.kind === "ok" ? loadState.versions : [];
  const actingVersionId =
    actionState.kind === "acting" ? actionState.versionId : null;
  const actingAction =
    actionState.kind === "acting" ? actionState.action : null;

  const activeVersionId = useMemo(
    () => versions.find((v) => v.status === "ACTIVE")?.versionId ?? null,
    [versions],
  );

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
          /gradual — 灰度发布
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          查看 KB 版本列表并执行灰度切换。后端灰度/回滚端点为 stub
          （Phase 36-T3 仅 UI happy path，无真实后端端点）。
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>1. KB selector</CardTitle>
          <CardDescription>
            Pick a kbId. Phase 36 ships with{" "}
            <code className="rounded bg-muted px-1.5 py-0.5">default</code>{" "}
            only.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <form
            onSubmit={handleKbSubmit}
            className="flex items-center gap-2"
          >
            <label
              htmlFor="gradualKbId"
              className="text-sm text-muted-foreground"
            >
              kbId
            </label>
            <input
              id="gradualKbId"
              type="text"
              value={draftKbId}
              onChange={(e) => setDraftKbId(e.target.value)}
              className="flex-1 rounded-md border border-input bg-background px-3 py-1.5 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              placeholder="default"
            />
            <Button type="submit" size="sm">
              Load
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={handleReload}
            >
              Reload
            </Button>
          </form>
        </CardContent>
      </Card>

      {/* Action feedback banner */}
      {actionState.kind === "done" && (
        <div className="mt-4 rounded-md border border-emerald-200 bg-emerald-50 p-3 text-sm text-emerald-900">
          {actionState.message}
        </div>
      )}
      {actionState.kind === "error" && (
        <div className="mt-4 rounded-md border border-destructive/50 bg-destructive/5 p-3 text-sm text-destructive">
          {actionState.message}
        </div>
      )}

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>2. Versions ({kbId})</CardTitle>
          <CardDescription>
            Version list from{" "}
            <code className="rounded bg-muted px-1.5 py-0.5">
              /api/agent/kb-versions/{`{kbId}`}
            </code>
            . "设为活跃" and "回滚" are stub endpoints (backend
            does not expose POST endpoints yet).
          </CardDescription>
        </CardHeader>
        <CardContent>
          {loadState.kind === "loading" && (
            <p className="text-sm text-muted-foreground">
              Loading versions…
            </p>
          )}
          {loadState.kind === "error" && (
            <div className="rounded-md border border-destructive/50 bg-destructive/5 p-3 text-sm text-destructive">
              <p className="font-medium">Failed to load versions</p>
              <p className="mt-1 text-xs">{loadState.message}</p>
              <Button
                onClick={handleReload}
                size="sm"
                variant="outline"
                className="mt-3"
              >
                Retry
              </Button>
            </div>
          )}
          {loadState.kind === "ok" && versions.length === 0 && (
            <p className="text-sm text-muted-foreground">
              No versions yet for kbId=
              <code className="rounded bg-muted px-1.5 py-0.5">{kbId}</code>.
              Upload one via{" "}
              <Link to="/ingest" className="underline">
                /ingest
              </Link>{" "}
              to create the first KbVersionMeta.
            </p>
          )}
          {loadState.kind === "ok" && versions.length > 0 && (
            <div className="overflow-x-auto">
              <table className="w-full text-sm">
                <thead>
                  <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                    <th className="py-2 pr-3">versionId</th>
                    <th className="py-2 pr-3">status</th>
                    <th className="py-2 pr-3">docCount</th>
                    <th className="py-2 pr-3">createdAt</th>
                    <th className="py-2 pr-3">action</th>
                  </tr>
                </thead>
                <tbody>
                  {versions.map((v) => (
                    <tr key={v.versionId} className="border-b last:border-0">
                      <td className="py-2 pr-3 font-mono text-xs">
                        {v.versionId}
                      </td>
                      <td className="py-2 pr-3">
                        <StatusBadge status={v.status} />
                      </td>
                      <td className="py-2 pr-3 font-mono">{v.docCount}</td>
                      <td className="py-2 pr-3 font-mono text-xs">
                        {formatTimestamp(v.createdAt)}
                      </td>
                      <td className="py-2 pr-3">
                        <div className="flex gap-2">
                          <Button
                            size="sm"
                            variant={
                              v.versionId === activeVersionId
                                ? "default"
                                : "outline"
                            }
                            disabled={
                              v.versionId === activeVersionId ||
                              (actingVersionId === v.versionId &&
                                actingAction === "activate")
                            }
                            onClick={() => handleActivate(v.versionId)}
                            data-testid={`gradual-activate-${v.versionId}`}
                          >
                            {v.versionId === activeVersionId
                              ? "活跃中"
                              : actingVersionId === v.versionId &&
                                  actingAction === "activate"
                                ? "切换中…"
                                : "设为活跃"}
                          </Button>
                          <Button
                            size="sm"
                            variant="secondary"
                            disabled={
                              v.versionId === activeVersionId ||
                              (actingVersionId === v.versionId &&
                                actingAction === "rollback")
                            }
                            onClick={() => handleRollback(v.versionId)}
                            data-testid={`gradual-rollback-${v.versionId}`}
                          >
                            {actingVersionId === v.versionId &&
                            actingAction === "rollback"
                              ? "回滚中…"
                              : "回滚"}
                          </Button>
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>Known limitations</CardTitle>
          <CardDescription>
            Phase 36-T3 scope — no backend gradual endpoints.
          </CardDescription>
        </CardHeader>
        <CardContent>
          <ul className="list-inside list-disc space-y-1 text-sm text-muted-foreground">
            <li>
              "设为活跃" and "回滚" are client-side stubs — they succeed
              locally but do not call a real backend endpoint.
            </li>
            <li>
              No JWT auth (Phase 34 scope). Tenant is hardcoded as "dev".
            </li>
            <li>
              The version list is fetched from the real{" "}
              <code className="rounded bg-muted px-1.5 py-0.5">
                GET /api/agent/kb-versions/{`{kbId}`}
              </code>{" "}
              endpoint (T2c ships this). The activate/rollback POST endpoints
              need a backend controller — out of Phase 36 scope.
            </li>
          </ul>
        </CardContent>
      </Card>
    </div>
  );
}
