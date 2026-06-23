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
 * VersionsPage — Phase 36-T2c.
 *
 * Wire flow (mirrors backend GET /api/agent/kb-versions/{kbId}):
 *   1. Operator enters a kbId (defaults to "default" — the only KB the
 *      Phase 35 + 36 backend ships with).
 *   2. versionsApi.listVersions(kbId) returns every KbVersionMeta for
 *      that KB under the X-Tenant-Id header (the dev tenant "dev" is
 *      injected by the axios interceptor-free flow; rag-app's
 *      MdcTenantFilter falls through when no auth gateway is in front).
 *   3. We render a versions table — newest first per backend's
 *      listVersions contract — and let the operator pick TWO versions
 *      (a "left" / baseline and a "right" / candidate) to diff.
 *   4. The diff panel below is a **metadata-level** diff, not a
 *      chunk-level diff. The backend exposes chunk *counters* on
 *      IngestJob (and chunk *content* via no endpoint today) but the
 *      KbVersion list endpoint only carries KbVersionMeta which holds
 *      counters + status + timestamps — not chunk text. So we diff
 *      what's actually available:
 *        - docCount delta (added / removed chunks, by counter)
 *        - status delta (e.g. STAGING → ACTIVE)
 *        - sourceLabel delta (text change)
 *        - createdAt / publishedAt gap
 *      The UI labels the panel "Metadata diff" so operators don't
 *      confuse it with chunk content.
 *
 * Why no chunk-level diff:
 *   The task body's primary contract was "v1 vs v2 chunks" but neither
 *   /api/ingest/compare nor any chunk-content endpoint exists in the
 *   current backend. Per the T2c body ("不要碰 backend") we cannot add
 *   one. We expose what's real — the metadata diff — and document the
 *   gap so the next phase can decide whether to ship a chunk-content
 *   endpoint and a true chunk-diff page.
 */

const KB_ID_DEFAULT = "default";

type LoadState =
  | { kind: "idle" }
  | { kind: "loading" }
  | { kind: "ok"; versions: KbVersionMeta[] }
  | { kind: "error"; message: string };

function formatTimestamp(iso?: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

function StatusBadge({ status }: { status: KbVersionStatus }) {
  // Map the backend's enum to a Tailwind palette. Matches PreviewPage's
  // StatusBadge styling for visual consistency across the admin UI.
  const palette: Record<KbVersionStatus, string> = {
    DRAFT: "bg-muted text-foreground",
    STAGING: "bg-blue-100 text-blue-800",
    ACTIVE: "bg-emerald-100 text-emerald-900",
    DEPRECATED: "bg-amber-100 text-amber-900",
  };
  return (
    <span
      className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${palette[status]}`}
      data-testid="versions-status-badge"
    >
      {status}
    </span>
  );
}

function formatDelta(delta: number, unit: string): string {
  if (delta === 0) return `±0 ${unit}`;
  const sign = delta > 0 ? "+" : "";
  // Class hooks for color coding — bg-green-100 (added), bg-red-100
  // (removed), bg-white (unchanged). The T2c spec picked these three
  // explicitly so we keep them as literal Tailwind classes (not
  // dynamic string interpolation, which Tailwind's purge step would
  // drop).
  return `${sign}${delta} ${unit}`;
}

interface DiffRow {
  field: string;
  left: string;
  right: string;
  /** "added" = right-only, "removed" = left-only, "changed" = both, "unchanged" = neither. */
  kind: "added" | "removed" | "changed" | "unchanged";
}

function buildDiffRows(
  left: KbVersionMeta,
  right: KbVersionMeta,
): DiffRow[] {
  const rows: DiffRow[] = [];

  rows.push({
    field: "docCount",
    left: String(left.docCount),
    right: String(right.docCount),
    kind:
      right.docCount > left.docCount
        ? "added"
        : right.docCount < left.docCount
          ? "removed"
          : "unchanged",
  });

  rows.push({
    field: "status",
    left: left.status,
    right: right.status,
    kind: left.status === right.status ? "unchanged" : "changed",
  });

  rows.push({
    field: "sourceLabel",
    left: left.sourceLabel ?? "—",
    right: right.sourceLabel ?? "—",
    kind:
      (left.sourceLabel ?? null) === (right.sourceLabel ?? null)
        ? "unchanged"
        : "changed",
  });

  rows.push({
    field: "createdAt",
    left: formatTimestamp(left.createdAt),
    right: formatTimestamp(right.createdAt),
    kind: left.createdAt === right.createdAt ? "unchanged" : "changed",
  });

  rows.push({
    field: "publishedAt",
    left: formatTimestamp(left.publishedAt),
    right: formatTimestamp(right.publishedAt),
    kind:
      (left.publishedAt ?? null) === (right.publishedAt ?? null)
        ? "unchanged"
        : "changed",
  });

  // Summary row — chunk delta in absolute terms, useful at-a-glance.
  const docDelta = right.docCount - left.docCount;
  rows.push({
    field: "Δ chunks (right − left)",
    left: formatDelta(0, "").trim() || "—",
    right: formatDelta(docDelta, ""),
    kind:
      docDelta > 0 ? "added" : docDelta < 0 ? "removed" : "unchanged",
  });

  return rows;
}

function diffRowClass(kind: DiffRow["kind"]): string {
  // Literal Tailwind classes — preserved by the purge step because
  // they appear as full string literals in source.
  switch (kind) {
    case "added":
      return "bg-green-100";
    case "removed":
      return "bg-red-100";
    case "changed":
      return "bg-amber-50";
    case "unchanged":
    default:
      return "bg-white";
  }
}

export function VersionsPage() {
  const [kbId, setKbId] = useState<string>(KB_ID_DEFAULT);
  const [draftKbId, setDraftKbId] = useState<string>(KB_ID_DEFAULT);
  const [state, setState] = useState<LoadState>({ kind: "idle" });
  const [leftId, setLeftId] = useState<number | null>(null);
  const [rightId, setRightId] = useState<number | null>(null);

  const fetchVersions = useCallback(async (id: string) => {
    setState({ kind: "loading" });
    try {
      const resp = await versionsApi.listVersions(id);
      setState({ kind: "ok", versions: resp.versions });
      // Default the selection to the two newest versions so the diff
      // panel shows something useful on first load. If only one
      // version exists, leave the second selection empty.
      const newest = resp.versions[0]?.versionId ?? null;
      const second = resp.versions[1]?.versionId ?? null;
      setLeftId(second);
      setRightId(newest);
    } catch (err) {
      setState({
        kind: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }, []);

  useEffect(() => {
    if (state.kind === "idle") {
      void fetchVersions(kbId);
    }
  }, [state.kind, kbId, fetchVersions]);

  const handleReload = () => {
    setState({ kind: "idle" });
  };

  const handleKbSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    const trimmed = draftKbId.trim();
    if (!trimmed) return;
    setKbId(trimmed);
    setState({ kind: "idle" });
  };

  const versions = state.kind === "ok" ? state.versions : [];
  const leftVersion = useMemo(
    () => versions.find((v) => v.versionId === leftId) ?? null,
    [versions, leftId],
  );
  const rightVersion = useMemo(
    () => versions.find((v) => v.versionId === rightId) ?? null,
    [versions, rightId],
  );

  const diffRows =
    leftVersion && rightVersion
      ? buildDiffRows(leftVersion, rightVersion)
      : [];

  return (
    <div className="container max-w-4xl py-10">
      <div className="mb-6">
        <Link to="/" className="text-sm text-muted-foreground hover:underline">
          ← Home
        </Link>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">
          /versions — KB Version Comparison
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Lists every KbVersionMeta for{" "}
          <code className="rounded bg-muted px-1.5 py-0.5">
            /api/agent/kb-versions/{`{kbId}`}
          </code>{" "}
          and renders a metadata-level diff between two selected versions.
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
            data-testid="versions-kb-form"
          >
            <label htmlFor="kbId" className="text-sm text-muted-foreground">
              kbId
            </label>
            <input
              id="kbId"
              type="text"
              value={draftKbId}
              onChange={(e) => setDraftKbId(e.target.value)}
              className="flex-1 rounded-md border border-input bg-background px-3 py-1.5 text-sm shadow-sm focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-ring"
              placeholder="default"
              data-testid="versions-kb-input"
            />
            <Button type="submit" size="sm">
              Load
            </Button>
            <Button
              type="button"
              size="sm"
              variant="outline"
              onClick={handleReload}
              data-testid="versions-reload"
            >
              Reload
            </Button>
          </form>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>2. Versions ({kbId})</CardTitle>
          <CardDescription>
            Backend returns versions newest→oldest per{" "}
            <code className="rounded bg-muted px-1.5 py-0.5">
              KbVersionService.listVersions
            </code>
            . Pick one as LEFT (baseline) and one as RIGHT (candidate).
          </CardDescription>
        </CardHeader>
        <CardContent>
          {state.kind === "loading" && (
            <p
              className="text-sm text-muted-foreground"
              data-testid="versions-loading"
            >
              Loading versions…
            </p>
          )}
          {state.kind === "error" && (
            <div
              className="rounded-md border border-destructive/50 bg-destructive/5 p-3 text-sm text-destructive"
              data-testid="versions-error"
            >
              <p className="font-medium">Failed to load versions</p>
              <p className="mt-1 text-xs">{state.message}</p>
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
          {state.kind === "ok" && versions.length === 0 && (
            <p
              className="text-sm text-muted-foreground"
              data-testid="versions-empty"
            >
              No versions yet for kbId=
              <code className="rounded bg-muted px-1.5 py-0.5">{kbId}</code>.
              Upload one via{" "}
              <Link to="/ingest" className="underline">
                /ingest
              </Link>{" "}
              to create the first KbVersionMeta.
            </p>
          )}
          {state.kind === "ok" && versions.length > 0 && (
            <div className="overflow-x-auto">
              <table
                className="w-full text-sm"
                data-testid="versions-table"
              >
                <thead>
                  <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                    <th className="py-2 pr-3">versionId</th>
                    <th className="py-2 pr-3">status</th>
                    <th className="py-2 pr-3">docCount</th>
                    <th className="py-2 pr-3">createdAt</th>
                    <th className="py-2 pr-3">publishedAt</th>
                    <th className="py-2 pr-3">sourceLabel</th>
                    <th className="py-2 pr-3">Pick</th>
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
                      <td className="py-2 pr-3 font-mono text-xs">
                        {formatTimestamp(v.publishedAt)}
                      </td>
                      <td className="py-2 pr-3 font-mono text-xs">
                        {v.sourceLabel ?? "—"}
                      </td>
                      <td className="py-2 pr-3">
                        <div className="flex gap-1">
                          <label className="flex items-center gap-1 text-xs">
                            <input
                              type="radio"
                              name="left"
                              checked={leftId === v.versionId}
                              onChange={() => setLeftId(v.versionId)}
                              data-testid={`versions-left-${v.versionId}`}
                            />
                            L
                          </label>
                          <label className="flex items-center gap-1 text-xs">
                            <input
                              type="radio"
                              name="right"
                              checked={rightId === v.versionId}
                              onChange={() => setRightId(v.versionId)}
                              data-testid={`versions-right-${v.versionId}`}
                            />
                            R
                          </label>
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
          <CardTitle>3. Metadata diff</CardTitle>
          <CardDescription>
            Compares LEFT (baseline) vs RIGHT (candidate). Color coding:
            bg-green-100 = added, bg-red-100 = removed, bg-amber-50 =
            changed, bg-white = unchanged. <strong>This is metadata only —
            no chunk content is fetched (no backend endpoint exists for
            chunk-level diff).</strong>
          </CardDescription>
        </CardHeader>
        <CardContent>
          {!leftVersion || !rightVersion ? (
            <p
              className="text-sm text-muted-foreground"
              data-testid="versions-diff-empty"
            >
              Pick one LEFT and one RIGHT version above to see the diff.
            </p>
          ) : leftVersion.versionId === rightVersion.versionId ? (
            <p
              className="text-sm text-muted-foreground"
              data-testid="versions-diff-same"
            >
              LEFT and RIGHT are the same version ({leftVersion.versionId}).
              Pick two different versions to compare.
            </p>
          ) : (
            <div className="overflow-x-auto">
              <table
                className="w-full text-sm"
                data-testid="versions-diff-table"
              >
                <thead>
                  <tr className="border-b text-left text-xs uppercase text-muted-foreground">
                    <th className="py-2 pr-3">Field</th>
                    <th className="py-2 pr-3">
                      LEFT v{leftVersion.versionId} ({leftVersion.status})
                    </th>
                    <th className="py-2 pr-3">
                      RIGHT v{rightVersion.versionId} ({rightVersion.status})
                    </th>
                  </tr>
                </thead>
                <tbody>
                  {diffRows.map((row) => (
                    <tr key={row.field} className="border-b last:border-0">
                      <td className="py-2 pr-3 font-mono text-xs">
                        {row.field}
                      </td>
                      <td
                        className={`py-2 pr-3 font-mono text-xs ${diffRowClass(row.kind)}`}
                      >
                        {row.left}
                      </td>
                      <td
                        className={`py-2 pr-3 font-mono text-xs ${diffRowClass(row.kind)}`}
                      >
                        {row.right}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}