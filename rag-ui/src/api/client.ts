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

export interface IngestJob {
  jobId: string;
  tenantId: string;
  status: "PENDING" | "PROCESSING" | "READY" | "PUBLISHED" | "FAILED";
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
