/**
 * RAG Admin API client — hand-typed against docs/openapi/openapi.json.
 *
 * In Phase 36-T1 we generate the *types* from the OpenAPI spec via
 * `npm run openapi:gen` (see package.json), but the runtime client
 * functions are written by hand for now. This keeps the scaffold
 * minimal and avoids forcing T2/T3 to wire up a second codegen
 * step before they even have a backend to talk to.
 *
 * The generated types live alongside the request/response models
 * below so the compiler verifies the wire shape on every call.
 */

import axios, { type AxiosInstance } from "axios";
import type { paths } from "./schema";

/**
 * Resolve a path parameterised by OpenAPI's `paths` type. If a route
 * does not exist in the spec, this returns `never` so the call site
 * gets a TypeScript error — a thin form of contract enforcement.
 */
type Path<M extends keyof paths, P extends keyof paths[M]> = paths[M][P];

/**
 * IngestJob — wire shape returned by GET /api/ingest/{jobId}.
 *
 * The backend's rag-core IngestJob record (see rag-core/.../model/IngestJob.java)
 * exposes the chunk pipeline as **counters** (totalChunks / embeddedChunks /
 * upsertedChunks / failedChunks) — it does NOT return the actual chunk text.
 * Phase 36-T2b's preview page surfaces those counters as a "Chunk Pipeline"
 * panel (semantically equivalent to "show me how this job split") without
 * needing a backend change. Surfacing the chunk text would require adding
 * a new endpoint (e.g. GET /api/ingest/{jobId}/chunks) — that's out of scope
 * for T2b per the task body's "不要碰 backend" rule.
 */
export interface IngestJob {
  jobId: string;
  tenantId: string;
  documentId?: string;
  kbVersion?: string | null;
  status: "PENDING" | "PROCESSING" | "READY" | "PUBLISHED" | "FAILED";
  /** Phase 36-T2b: chunk pipeline counters from rag-core IngestJob record. */
  totalChunks?: number;
  embeddedChunks?: number;
  upsertedChunks?: number;
  failedChunks?: number;
  createdAt?: string;
  updatedAt?: string;
  documentCount?: number;
  errorMessage?: string | null;
}

export interface IngestRequest {
  tenantId?: string;
  sourceUri: string;
  contentType?: string;
  tags?: string[];
}

export interface Query {
  userId: string;
  sessionId?: string | null;
  rawText: string;
  permissionTags?: string[];
  topK?: number;
  kbVersion?: string | null;
}

export interface Answer {
  answerText: string;
  source: "CACHE" | "LLM" | "FALLBACK_RULE";
  citations?: Array<{
    sourceUri: string;
    snippet: string;
    score: number;
  }>;
}

const http: AxiosInstance = axios.create({
  baseURL: import.meta.env.VITE_API_BASE ?? "/api",
  headers: { "Content-Type": "application/json" },
});

/**
 * RAG Ingest API.
 *
 * DoD: at least one of these functions must be importable from
 * `rag-ui/src/api/client.ts` for the Phase 36-T1 task to be considered
 * shipped. T2 will add a `listJobs()` helper once the backend exposes
 * `/api/ingest/jobs` (Phase 35 currently exposes per-job GET only).
 */
/**
 * Metadata that travels in the `request` part of the multipart upload.
 * Mirrors the JSON shape the IngestController expects — we hand-encode
 * it as a Blob so the wire field is `Content-Type: application/json`
 * (Spring's @RequestPart("request") binding is content-type-sensitive).
 *
 * Phase 36-T2a wire contract (Phase 35 narrow-scope rebuild v2):
 *   POST /api/ingest/multipart
 *     part "file"    : application/pdf
 *     part "request" : application/json (this shape)
 *   → 202 Accepted + IngestJob
 *
 * The kbId dropdown in /ingest defaults to "default" so the user can
 * drag-drop a PDF without picking a KB first; the controller treats
 * kbId="default" as a real value (no special-casing in backend).
 */
export interface MultipartIngestMeta {
  kbId: string;
  documentId: string;
  documentVersion: number;
  title: string;
  sourceUri: string;
  permissionTags?: string[];
  /** Required by the backend @Valid, but the file wraps as a single-section Document server-side — send an empty list. */
  sections: Array<{ heading?: string; content: string }>;
}

export const ingestApi = {
  /** GET /api/ingest/{jobId} — fetch a job snapshot. */
  getJob: async (jobId: string): Promise<IngestJob> => {
    const { data } = await http.get<IngestJob>(
      `/ingest/${encodeURIComponent(jobId)}`,
    );
    return data;
  },

  /** POST /api/ingest — submit a JSON ingest request. */
  submit: async (req: IngestRequest): Promise<IngestJob> => {
    const { data } = await http.post<IngestJob>("/ingest", req);
    return data;
  },

  /**
   * POST /api/ingest/multipart — upload a single PDF + JSON metadata.
   * Uses XMLHttpRequest (not axios) because we need the upload `progress`
   * event for the on-screen progress bar; axios's request-body progress
   * hook only works inside a browser fetch/XHR.
   *
   * Returns the freshly-created IngestJob on 202 Accepted. Rejects with
   * the axios-flavoured Error on 4xx (the controller's ProblemDetail
   * lands in `error.response.data` — `title` + `detail` fields are the
   * user-facing message).
   */
  uploadMultipart: (args: {
    file: File;
    meta: MultipartIngestMeta;
    onProgress?: (percent: number) => void;
    signal?: AbortSignal;
  }): Promise<IngestJob> => {
    return new Promise<IngestJob>((resolve, reject) => {
      const xhr = new XMLHttpRequest();
      const form = new FormData();
      // The "file" part — backend does @RequestPart("file") MultipartFile.
      form.append("file", args.file, args.file.name);
      // The "request" part — JSON-encoded so @RequestPart("request") binding
      // sees application/json. Sending an object would let the browser pick
      // text/plain; a Blob forces the right Content-Type.
      form.append(
        "request",
        new Blob([JSON.stringify(args.meta)], {
          type: "application/json",
        }),
        "request.json",
      );

      xhr.open("POST", "/ingest/multipart");
      // Tenant header is mandatory on /api/* — the rag-app controller's
      // `requiredTenant` throws 401 if blank. The dev default "dev" is
      // wired in MdcTenantFilter to fall through when no auth gateway is
      // in front (Phase 36 dev story — production would inject this
      // upstream). The backend rag-app also requires X-Tenant-Id to be
      // non-blank; we send "dev" explicitly so callers don't have to
      // remember to set it in the UI.
      xhr.setRequestHeader("X-Tenant-Id", "dev");
      // axios sets Accept: application/json, text/plain, */* by default;
      // we mirror that so 4xx ProblemDetail bodies don't get opaque.
      xhr.setRequestHeader("Accept", "application/json");

      xhr.upload.addEventListener("progress", (ev) => {
        if (ev.lengthComputable && args.onProgress) {
          args.onProgress(Math.round((ev.loaded / ev.total) * 100));
        }
      });
      xhr.addEventListener("load", () => {
        if (xhr.status >= 200 && xhr.status < 300) {
          try {
            resolve(JSON.parse(xhr.responseText) as IngestJob);
          } catch (parseErr) {
            reject(
              new Error(
                `uploadMultipart: backend returned ${xhr.status} but body was not JSON: ${xhr.responseText.slice(0, 200)}`,
              ),
            );
          }
        } else {
          // 4xx/5xx — surface the ProblemDetail or raw body so the UI
          // can show "Unsupported Media Type" / "Missing X-Tenant-Id" etc.
          let detail = xhr.responseText;
          try {
            const parsed = JSON.parse(detail) as {
              title?: string;
              detail?: string;
            };
            detail = parsed.detail || parsed.title || detail;
          } catch {
            // not JSON, keep raw
          }
          reject(
            new Error(
              `uploadMultipart: HTTP ${xhr.status} ${xhr.statusText} — ${detail}`,
            ),
          );
        }
      });
      xhr.addEventListener("error", () => {
        reject(new Error("uploadMultipart: network error (XHR fired `error`)"));
      });
      xhr.addEventListener("abort", () => {
        reject(new Error("uploadMultipart: aborted by caller"));
      });
      if (args.signal) {
        args.signal.addEventListener("abort", () => xhr.abort());
      }
      xhr.send(form);
    });
  },

  /** POST /api/ingest/{jobId}/publish — promote READY → PUBLISHED. */
  publish: async (jobId: string): Promise<IngestJob> => {
    const { data } = await http.post<IngestJob>(
      `/ingest/${encodeURIComponent(jobId)}/publish`,
    );
    return data;
  },
};

export const qaApi = {
  /** POST /api/qa — run the online QA chain. */
  submit: async (q: Query): Promise<Answer> => {
    const { data } = await http.post<Answer>("/qa", q);
    return data;
  },
};

// Re-export the paths type for downstream T2/T3 to use when they
// wire up codegen output. The current `paths` import is intentionally
// a `type` import — it produces no runtime JS, only type-level links.
export type OpenApiPaths = Path<"/api/ingest/{jobId}", "get">;
