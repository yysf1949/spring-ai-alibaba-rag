import { useCallback, useEffect, useRef, useState } from "react";
import { useDropzone } from "react-dropzone";
import { useNavigate } from "react-router-dom";
import { Link } from "react-router-dom";
import { Button } from "@/components/ui/button";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { ingestApi, type IngestJob } from "@/api/client";

/**
 * IngestPage — Phase 36-T2a.
 *
 * Wire flow (mirrors backend POST /api/ingest/multipart):
 *   1. User drag-drops one PDF (or clicks to pick). Other content types
 *      are rejected client-side via react-dropzone `accept` so the user
 *      sees a friendly error before the upload round-trip.
 *   2. User picks a KB (default "default"), then clicks Upload.
 *   3. ingestApi.uploadMultipart posts the file + JSON metadata as 2-part
 *      multipart (XHR so we get upload progress).
 *   4. On 202, we get { jobId, status: PENDING }. We poll
 *      GET /api/ingest/{jobId} every 1s until the job reaches a terminal
 *      state (PUBLISHED / READY / FAILED). The Phase 35 backend doesn't
 *      auto-publish — a human / T2b "Preview & Publish" button does.
 *      For T2a we navigate to /preview/{jobId} once it hits READY or
 *      PUBLISHED (whichever comes first in the loop).
 *   5. On 4xx/5xx, we show the backend ProblemDetail's `detail` field
 *      (e.g. "Multipart file must have Content-Type=application/pdf,
 *      got: image/png").
 *
 * Out of scope for T2a (left to T2b / T2c / T3):
 *   - Multi-file batch upload
 *   - Editing the JSON IngestRequest's title / permissionTags before upload
 *     (T2a auto-fills documentId from filename, documentVersion=1)
 *   - /preview/{jobId} route (T2b)
 *   - /versions route (T2c)
 *   - Auth + rate limit (Phase 34 + T3 stub)
 */

const KB_OPTIONS = [
  { value: "default", label: "default (default KB)" },
  // Phase 35 + 36 ship with a single hard-coded KB; future PRs will
  // surface a /api/kb list endpoint and populate this dynamically.
];

const ACCEPT_PDF = { "application/pdf": [".pdf"] };

type UploadPhase =
  | { kind: "idle" }
  | { kind: "uploading"; percent: number }
  | { kind: "polling"; jobId: string; status: IngestJob["status"] }
  | { kind: "ready"; jobId: string; status: IngestJob["status"] }
  | { kind: "failed"; jobId: string; error: string };

function formatBytes(n: number): string {
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

export function IngestPage() {
  const navigate = useNavigate();
  const [file, setFile] = useState<File | null>(null);
  const [kbId, setKbId] = useState<string>("default");
  const [phase, setPhase] = useState<UploadPhase>({ kind: "idle" });
  // Track the active XHR so we can abort on unmount or "Cancel" click.
  const abortRef = useRef<AbortController | null>(null);

  const onDrop = useCallback((accepted: File[], rejected: unknown[]) => {
    if (rejected.length > 0) {
      // react-dropzone rejected for a non-PDF — surface the reason.
      const reason = (rejected[0] as { errors?: Array<{ message: string }> })
        ?.errors?.[0]?.message;
      setPhase({
        kind: "failed",
        jobId: "",
        error: reason || "File rejected. Only PDF files are accepted.",
      });
      setFile(null);
      return;
    }
    if (accepted.length > 0) {
      setFile(accepted[0]);
      // Reset the phase so a fresh drop clears any prior error banner.
      setPhase({ kind: "idle" });
    }
  }, []);

  const { getRootProps, getInputProps, isDragActive } = useDropzone({
    onDrop,
    accept: ACCEPT_PDF,
    multiple: false,
    maxFiles: 1,
  });

  // Poll job status every 1s while we're in the polling phase. The
  // cleanup function aborts any in-flight timer on unmount / phase
  // transition so we don't leak setInterval handles.
  useEffect(() => {
    if (phase.kind !== "polling") return;
    let cancelled = false;
    const tick = async () => {
      try {
        const job = await ingestApi.getJob(phase.jobId);
        if (cancelled) return;
        if (job.status === "PUBLISHED" || job.status === "READY") {
          setPhase({ kind: "ready", jobId: phase.jobId, status: job.status });
        } else if (job.status === "FAILED") {
          setPhase({
            kind: "failed",
            jobId: phase.jobId,
            error: job.errorMessage || "Ingest pipeline failed (no detail)",
          });
        } else {
          // still PENDING / PROCESSING — update status display
          setPhase({ kind: "polling", jobId: phase.jobId, status: job.status });
        }
      } catch (err) {
        if (cancelled) return;
        setPhase({
          kind: "failed",
          jobId: phase.jobId,
          error: err instanceof Error ? err.message : String(err),
        });
      }
    };
    const handle = window.setInterval(tick, 1000);
    // Kick the first tick immediately so the UI updates faster than 1s.
    tick();
    return () => {
      cancelled = true;
      window.clearInterval(handle);
    };
  }, [phase.kind === "polling" ? phase.jobId : null, phase.kind]);

  // Cleanup the XHR on unmount — defensive against the user navigating
  // away mid-upload.
  useEffect(() => {
    return () => {
      abortRef.current?.abort();
    };
  }, []);

  const handleUpload = async () => {
    if (!file) return;
    setPhase({ kind: "uploading", percent: 0 });
    const controller = new AbortController();
    abortRef.current = controller;

    // The backend's multipart IngestRequest shape (see IngestController):
    //   kbId, documentId, documentVersion, title, sourceUri, permissionTags?, sections
    // We auto-fill documentId / title from the filename; sections is
    // intentionally empty — the backend wraps the uploaded file as a
    // single-section Document server-side and parses the PDF in a
    // downstream stage.
    const meta = {
      kbId,
      documentId: file.name.replace(/\.pdf$/i, ""),
      documentVersion: 1,
      title: file.name,
      sourceUri: `upload://${file.name}`,
      sections: [{ heading: "", content: "" }],
    };

    try {
      const job = await ingestApi.uploadMultipart({
        file,
        meta,
        signal: controller.signal,
        onProgress: (percent) => {
          // Only update if still in `uploading` — once the response
          // arrives we transition to `polling` and stop the bar.
          setPhase((prev) =>
            prev.kind === "uploading" ? { kind: "uploading", percent } : prev,
          );
        },
      });
      setPhase({ kind: "polling", jobId: job.jobId, status: job.status });
    } catch (err) {
      setPhase({
        kind: "failed",
        jobId: "",
        error: err instanceof Error ? err.message : String(err),
      });
    }
  };

  const handleCancel = () => {
    abortRef.current?.abort();
    abortRef.current = null;
    setPhase({ kind: "idle" });
  };

  const handleReset = () => {
    setFile(null);
    setPhase({ kind: "idle" });
  };

  const handlePreview = () => {
    if (phase.kind === "ready") {
      navigate(`/preview/${encodeURIComponent(phase.jobId)}`);
    }
  };

  return (
    <div className="container max-w-3xl py-10">
      <div className="mb-6">
        <Link to="/" className="text-sm text-muted-foreground hover:underline">
          ← Home
        </Link>
        <h1 className="mt-2 text-3xl font-bold tracking-tight">/ingest — Drag &amp; Drop PDF Upload</h1>
        <p className="mt-1 text-sm text-muted-foreground">
          Drops one PDF, posts it to <code className="rounded bg-muted px-1.5 py-0.5">/api/ingest/multipart</code>,
          polls <code className="rounded bg-muted px-1.5 py-0.5">/api/ingest/{`{jobId}`}</code>,
          then jumps to <code className="rounded bg-muted px-1.5 py-0.5">/preview/{`{jobId}`}</code> when ready.
        </p>
      </div>

      <Card>
        <CardHeader>
          <CardTitle>1. Pick a PDF</CardTitle>
          <CardDescription>Drag a single PDF onto the drop zone, or click to browse.</CardDescription>
        </CardHeader>
        <CardContent>
          <div
            {...getRootProps()}
            className={`cursor-pointer rounded-md border-2 border-dashed p-8 text-center transition-colors ${
              isDragActive
                ? "border-primary bg-primary/5"
                : "border-muted-foreground/25 hover:border-primary/50"
            }`}
            data-testid="ingest-dropzone"
          >
            <input {...getInputProps()} />
            {file ? (
              <div className="space-y-1">
                <p className="font-medium">{file.name}</p>
                <p className="text-sm text-muted-foreground">{formatBytes(file.size)}</p>
              </div>
            ) : isDragActive ? (
              <p className="text-sm text-muted-foreground">Drop the PDF here…</p>
            ) : (
              <p className="text-sm text-muted-foreground">
                Drag a PDF here, or click to select a file
              </p>
            )}
          </div>
        </CardContent>
      </Card>

      <Card className="mt-6">
        <CardHeader>
          <CardTitle>2. Pick a KB &amp; Upload</CardTitle>
          <CardDescription>KB id defaults to &quot;default&quot;; the backend treats any string as a valid kbId.</CardDescription>
        </CardHeader>
        <CardContent>
          <div className="flex items-end gap-4">
            <div className="flex-1">
              <label htmlFor="kb-select" className="text-sm font-medium">
                KB
              </label>
              <select
                id="kb-select"
                value={kbId}
                onChange={(e) => setKbId(e.target.value)}
                disabled={phase.kind === "uploading" || phase.kind === "polling"}
                className="mt-1 block w-full rounded-md border border-input bg-background px-3 py-2 text-sm"
              >
                {KB_OPTIONS.map((opt) => (
                  <option key={opt.value} value={opt.value}>
                    {opt.label}
                  </option>
                ))}
              </select>
            </div>
            <div className="flex gap-2">
              {phase.kind === "uploading" ? (
                <Button onClick={handleCancel} variant="outline">
                  Cancel
                </Button>
              ) : (
                <Button
                  onClick={handleUpload}
                  disabled={!file || phase.kind === "polling"}
                >
                  Upload
                </Button>
              )}
            </div>
          </div>

          {/* Progress bar — visible while uploading. */}
          {phase.kind === "uploading" && (
            <div className="mt-4 space-y-1">
              <div className="h-2 w-full overflow-hidden rounded-full bg-muted">
                <div
                  className="h-full bg-primary transition-all"
                  style={{ width: `${phase.percent}%` }}
                />
              </div>
              <p className="text-xs text-muted-foreground">{phase.percent}%</p>
            </div>
          )}

          {/* Status panel — visible while polling OR after success / failure. */}
          {(phase.kind === "polling" ||
            phase.kind === "ready" ||
            phase.kind === "failed") && (
            <div
              className={`mt-4 rounded-md border p-3 text-sm ${
                phase.kind === "failed"
                  ? "border-destructive/50 bg-destructive/5 text-destructive"
                  : phase.kind === "ready"
                  ? "border-green-600/50 bg-green-50 text-green-700"
                  : "border-muted bg-muted/30 text-foreground"
              }`}
              data-testid="ingest-status"
            >
              {phase.kind === "polling" && (
                <p>
                  Job <code className="rounded bg-background px-1.5 py-0.5">{phase.jobId}</code>{" "}
                  — status: <strong>{phase.status}</strong> (polling every 1s)
                </p>
              )}
              {phase.kind === "ready" && (
                <div className="space-y-2">
                  <p>
                    Job <code className="rounded bg-background px-1.5 py-0.5">{phase.jobId}</code>{" "}
                    reached status <strong>{phase.status}</strong>.
                  </p>
                  <div className="flex gap-2">
                    <Button onClick={handlePreview} size="sm">
                      Open Preview
                    </Button>
                    <Button onClick={handleReset} size="sm" variant="outline">
                      Upload another
                    </Button>
                  </div>
                </div>
              )}
              {phase.kind === "failed" && (
                <div className="space-y-2">
                  <p className="font-medium">Upload / ingest failed</p>
                  <p className="text-xs">{phase.error}</p>
                  {phase.jobId && (
                    <p className="text-xs text-muted-foreground">
                      jobId: <code>{phase.jobId}</code>
                    </p>
                  )}
                  <Button onClick={handleReset} size="sm" variant="outline">
                    Try again
                  </Button>
                </div>
              )}
            </div>
          )}
        </CardContent>
      </Card>
    </div>
  );
}