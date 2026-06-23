import { useCallback, useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { Button } from "@/components/ui/button";
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from "@/components/ui/card";
import { ingestApi, type IngestJob } from "@/api/client";

/**
 * PreviewPage — Phase 36-T2b.
 *
 * Wire flow (mirrors backend GET /api/ingest/{jobId}):
 *   1. useParams<{ jobId: string }>() pulls the jobId from the URL.
 *   2. ingestApi.getJob(jobId) returns the live IngestJob snapshot.
 *   3. We render three panels:
 *        - Status banner: PENDING / PROCESSING / READY / PUBLISHED / FAILED
 *          (FAILED surfaces backend `errorMessage`).
 *        - Job metadata: documentId, kbVersion, timestamps.
 *        - Chunk pipeline counters: total / embedded / upserted / failed.
 *          These are what the rag-core IngestJob record exposes today
 *          (see rag-core/.../model/IngestJob.java). Surfacing the
 *          actual chunk text would require a new GET /api/ingest/{jobId}/chunks
 *          endpoint — out of scope for T2b.
 *
 * Why we don't fetch the chunk text:
 *   The task body explicitly says "不要碰 backend". The current
 *   GET /api/ingest/{jobId} returns only counters — not chunk content.
 *   For Phase 36-T2b we show the counters as a "Chunk Pipeline" panel,
 *   which gives the operator enough info to spot a stuck job (e.g.
 *   embeddedChunks < totalChunks after the job reached READY) without
 *   adding backend scope.
 *
 * Out of scope for T2b (left to later tasks):
 *   - Chunk-level text / metadata / highlighting
 *   - Publish button (T2c / Phase 37?)
 *   - /versions/{kbId} route
 *   - Auth + rate limit
 */

type LoadState =
  | { kind: "loading" }
  | { kind: "ok"; job: IngestJob }
  | { kind: "error"; message: string };

function StatusBadge({ status }: { status: IngestJob["status"] }) {
  const palette: Record<IngestJob["status"], string> = {
    PENDING: "bg-muted text-foreground",
    PROCESSING: "bg-blue-100 text-blue-800",
    READY: "bg-green-100 text-green-800",
    PUBLISHED: "bg-emerald-100 text-emerald-900",
    FAILED: "bg-red-100 text-red-800",
  };
  return (
    <span
      className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${palette[status]}`}
      data-testid="preview-status-badge"
    >
      {status}
    </span>
  );
}

function formatPercent(num: number, denom: number): string {
  if (denom <= 0) return "—";
  return `${Math.round((num / denom) * 100)}%`;
}

function formatTimestamp(iso?: string): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

export function PreviewPage() {
  const { jobId } = useParams<{ jobId: string }>();
  const [state, setState] = useState<LoadState>({ kind: "loading" });

  const fetchJob = useCallback(async (id: string) => {
    setState({ kind: "loading" });
    try {
      const job = await ingestApi.getJob(id);
      setState({ kind: "ok", job });
    } catch (err) {
      setState({
        kind: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  }, []);

  // Initial load — re-run if the URL jobId changes (operator pastes
  // a different jobId into the address bar).
  useEffect(() => {
    if (!jobId) {
      setState({ kind: "error", message: "No jobId in URL." });
      return;
    }
    void fetchJob(jobId);
  }, [jobId, fetchJob]);

  const handleRefresh = () => {
    if (jobId) void fetchJob(jobId);
  };

  const handlePublish = async () => {
    if (state.kind !== "ok") return;
    try {
      const job = await ingestApi.publish(state.job.jobId);
      setState({ kind: "ok", job });
    } catch (err) {
      setState({
        kind: "error",
        message: err instanceof Error ? err.message : String(err),
      });
    }
  };

  return (
    <div className="container max-w-3xl py-10">
      <div className="mb-6">
        <Link to="/" className="text-sm text-muted-foreground hover:underline">
          ← Home
        </Link>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">
          /preview — Chunk Pipeline
        </h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Polls{" "}
          <code className="rounded bg-muted px-1.5 py-0.5">
            /api/ingest/{`{jobId}`}
          </code>{" "}
          and renders the chunk-pipeline counters for{" "}
          <code className="rounded bg-muted px-1.5 py-0.5">{jobId}</code>.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>1. Job status</CardTitle>
          <CardDescription>
            Live snapshot from the rag-core ingest pipeline.
          </CardDescription>
        </CardHeader>
        <CardContent>
          {state.kind === "loading" && (
            <p
              className="text-sm text-muted-foreground"
              data-testid="preview-loading"
            >
              Loading job…
            </p>
          )}
          {state.kind === "error" && (
            <div
              className="rounded-md border border-destructive/50 bg-destructive/5 p-3 text-sm text-destructive"
              data-testid="preview-error"
            >
              <p className="font-medium">Failed to load job</p>
              <p className="mt-1 text-xs">{state.message}</p>
              <Button
                onClick={handleRefresh}
                size="sm"
                variant="outline"
                className="mt-3"
              >
                Retry
              </Button>
            </div>
          )}
          {state.kind === "ok" && (
            <div className="space-y-3" data-testid="preview-status">
              <div className="flex items-center gap-3">
                <StatusBadge status={state.job.status} />
                <span className="text-sm text-muted-foreground">
                  jobId:{" "}
                  <code className="rounded bg-muted px-1.5 py-0.5">
                    {state.job.jobId}
                  </code>
                </span>
              </div>
              {state.job.status === "FAILED" && state.job.errorMessage && (
                <p className="rounded-md border border-destructive/50 bg-destructive/5 p-3 text-sm text-destructive">
                  {state.job.errorMessage}
                </p>
              )}
              <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                <dt className="text-muted-foreground">documentId</dt>
                <dd className="font-mono text-xs">
                  {state.job.documentId ?? "—"}
                </dd>
                <dt className="text-muted-foreground">kbVersion</dt>
                <dd className="font-mono text-xs">
                  {state.job.kbVersion ?? "—"}
                </dd>
                <dt className="text-muted-foreground">createdAt</dt>
                <dd className="font-mono text-xs">
                  {formatTimestamp(state.job.createdAt)}
                </dd>
                <dt className="text-muted-foreground">updatedAt</dt>
                <dd className="font-mono text-xs">
                  {formatTimestamp(state.job.updatedAt)}
                </dd>
              </dl>
              <div className="flex gap-2 pt-2">
                <Button onClick={handleRefresh} size="sm" variant="outline">
                  Refresh
                </Button>
                {state.job.status === "READY" && (
                  <Button onClick={handlePublish} size="sm">
                    Publish
                  </Button>
                )}
                <Button asChild size="sm" variant="ghost">
                  <Link to="/ingest">Upload another</Link>
                </Button>
              </div>
            </div>
          )}
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>2. Chunk pipeline</CardTitle>
          <CardDescription>
            Counters from rag-core IngestJob. T2b does not surface chunk text
            (that would need a new GET /api/ingest/{`{jobId}`}/chunks endpoint).
          </CardDescription>
        </CardHeader>
        <CardContent>
          {state.kind === "ok" ? (
            <dl
              className="grid grid-cols-2 gap-x-4 gap-y-3 text-sm"
              data-testid="preview-chunk-counters"
            >
              <dt className="text-muted-foreground">totalChunks</dt>
              <dd className="font-mono">{state.job.totalChunks ?? 0}</dd>
              <dt className="text-muted-foreground">embeddedChunks</dt>
              <dd className="font-mono">
                {state.job.embeddedChunks ?? 0}{" "}
                <span className="text-xs text-muted-foreground">
                  (
                  {formatPercent(
                    state.job.embeddedChunks ?? 0,
                    state.job.totalChunks ?? 0,
                  )}
                  )
                </span>
              </dd>
              <dt className="text-muted-foreground">upsertedChunks</dt>
              <dd className="font-mono">
                {state.job.upsertedChunks ?? 0}{" "}
                <span className="text-xs text-muted-foreground">
                  (
                  {formatPercent(
                    state.job.upsertedChunks ?? 0,
                    state.job.totalChunks ?? 0,
                  )}
                  )
                </span>
              </dd>
              <dt className="text-muted-foreground">failedChunks</dt>
              <dd
                className={`font-mono ${
                  (state.job.failedChunks ?? 0) > 0
                    ? "text-destructive"
                    : ""
                }`}
              >
                {state.job.failedChunks ?? 0}
              </dd>
            </dl>
          ) : (
            <p className="text-sm text-muted-foreground">
              {state.kind === "loading"
                ? "Loading counters…"
                : "Counters unavailable."}
            </p>
          )}
        </CardContent>
      </Card>
    </div>
  );
}