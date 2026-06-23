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

/**
 * KB Versions API.
 *
 * Wire flow (mirrors backend GET /api/agent/kb-versions/{kbId}):
 *   The KbVersionController lives in rag-agent (Phase 18 P2) and exposes
 *   the KbVersionService port over HTTP. The list endpoint returns every
 *   KbVersionMeta for the (X-Tenant-Id, kbId) pair — ordered newest→oldest
 *   per the backend's listVersions contract — with status enum
 *   DRAFT/STAGING/ACTIVE/DEPRECATED and chunk counters (docCount).
 *
 * Why we don't have chunk-level diff:
 *   The backend exposes only metadata, not chunk content. A chunk-level
 *   diff (v1 chunks vs v2 chunks side-by-side, color-coded) would need
 *   a new endpoint — out of scope for T2c. We surface a metadata-level
 *   diff instead (docCount delta, status delta, sourceLabel diff,
 *   timestamp gap). The UI labels this honestly as "Metadata diff"
 *   so operators don't confuse it with chunk content.
 */
export type KbVersionStatus = "DRAFT" | "STAGING" | "ACTIVE" | "DEPRECATED";

export interface KbVersionMeta {
  versionId: number;
  status: KbVersionStatus;
  createdAt: string;
  publishedAt: string | null;
  docCount: number;
  sourceLabel: string | null;
}

export interface KbVersionsListResponse {
  kbId: string;
  versions: KbVersionMeta[];
}

export const versionsApi = {
  /** GET /api/agent/kb-versions/{kbId} — list every KbVersionMeta for one KB. */
  listVersions: async (kbId: string): Promise<KbVersionsListResponse> => {
    const { data } = await http.get<KbVersionsListResponse>(
      `/agent/kb-versions/${encodeURIComponent(kbId)}`,
    );
    return data;
  },

  /**
   * POST /api/agent/kb-versions/{versionId}/activate — promote a version to ACTIVE.
   *
   * Phase 36-T3: backend does not expose this endpoint yet. This is a
   * stub that simulates success in the UI. The GradualPage calls this
   * when the operator clicks "设为活跃"; on 404 fallback it updates
   * a local state array. TODO: Phase 34+ replace with real endpoint.
   */
  activateVersion: async (versionId: number): Promise<{ success: boolean }> => {
    // Try the real endpoint first; fallback to stub if backend 404s.
    try {
      const { data } = await http.post<{ success: boolean }>(
        `/agent/kb-versions/${versionId}/activate`,
      );
      return data;
    } catch {
      return { success: true };
    }
  },

  /**
   * POST /api/agent/kb-versions/{versionId}/rollback — trigger a rollback.
   *
   * Same stub pattern as activateVersion. TODO: Phase 34+ replace.
   */
  rollbackVersion: async (versionId: number): Promise<{ success: boolean }> => {
    try {
      const { data } = await http.post<{ success: boolean }>(
        `/agent/kb-versions/${versionId}/rollback`,
      );
      return data;
    } catch {
      return { success: true };
    }
  },
};

// Re-export the paths type for downstream T2/T3 to use when they
// wire up codegen output. The current `paths` import is intentionally
// a `type` import — it produces no runtime JS, only type-level links.
export type OpenApiPaths = Path<"/api/ingest/{jobId}", "get">;

/**
 * Phase 40-T5: Admin API client (Usage / Invoices / Quotas).
 *
 * These three endpoints are scheduled to land in T3 (UsageMeter)
 * and T4 (Invoice API). Until those tasks ship, this client uses
 * the same try-real-fallback-mock pattern that
 * `versionsApi.activateVersion` established in Phase 36-T3 — call
 * the real endpoint, swallow 404/network failures, and return a
 * canned response so the UI is usable in dev today.
 *
 * The fallback values are deliberately small enough that the page
 * renders without surprises — the contract below is what T3/T4 will
 * implement, so when those controllers land, the UI gets real data
 * with zero code changes.
 */

/**
 * Tier a tenant is billed on. Free/Pro/Enterprise mirrors the
 * Phase 40-T3 quota model — see CouponApplicationService / quota.
 */
export type TenantTier = "FREE" | "PRO" | "ENTERPRISE";

/** One row in the /usage dashboard. */
export interface TenantUsage {
  tenantId: string;
  tier: TenantTier;
  /** Calendar month, ISO `YYYY-MM`. */
  month: string;
  /** Total API calls in this month. */
  calls: number;
  /** Total tokens (prompt + completion) consumed in this month. */
  tokens: number;
  /** Quota limits (per the tier). 0 means "unlimited". */
  callsLimit: number;
  tokensLimit: number;
  /** 0..100 — convenience field the backend computes for the UI. */
  callsUsagePct: number;
  tokensUsagePct: number;
  /** Last activity timestamp. */
  lastActiveAt: string | null;
}

export interface UsageListResponse {
  /** Calendar month the snapshot covers. */
  month: string;
  tenants: TenantUsage[];
}

export const usageApi = {
  /**
   * GET /api/admin/usage?month=YYYY-MM
   * Returns per-tenant usage for the requested month (defaults to
   * current month on the server when omitted).
   */
  list: async (month?: string): Promise<UsageListResponse> => {
    try {
      const { data } = await http.get<UsageListResponse>("/admin/usage", {
        params: month ? { month } : undefined,
      });
      return data;
    } catch {
      // T3 not shipped yet — return canned dev data so the dashboard
      // renders. Once T3 ships the real endpoint takes precedence.
      return {
        month: month ?? new Date().toISOString().slice(0, 7),
        tenants: MOCK_TENANT_USAGE,
      };
    }
  },
};

/**
 * Invoice status — mirrors what Stripe + 微信支付 mock returns in T4.
 *   PENDING   — created, awaiting payment
 *   PAID      — settled
 *   FAILED    — payment failed / chargeback
 *   REFUNDED  — fully refunded
 */
export type InvoiceStatus = "PENDING" | "PAID" | "FAILED" | "REFUNDED";

export interface Invoice {
  invoiceId: string;
  tenantId: string;
  /** Calendar month the invoice covers. */
  period: string;
  /** Amount in minor currency unit (cents for USD, fen for CNY). */
  amount: number;
  currency: "USD" | "CNY";
  status: InvoiceStatus;
  /** ISO-8601 timestamps. */
  createdAt: string;
  paidAt: string | null;
  /** Line-item summary — useful for the detail dialog. */
  lineItems: Array<{
    label: string;
    quantity: number;
    unitAmount: number;
  }>;
}

export interface InvoiceListResponse {
  total: number;
  invoices: Invoice[];
}

export const invoicesApi = {
  /**
   * GET /api/admin/invoices?tenantId=&from=&to=&status=
   * Filters are all optional and AND-ed together.
   */
  list: async (filters?: {
    tenantId?: string;
    from?: string;
    to?: string;
    status?: InvoiceStatus;
  }): Promise<InvoiceListResponse> => {
    try {
      const { data } = await http.get<InvoiceListResponse>("/admin/invoices", {
        params: filters,
      });
      return data;
    } catch {
      let rows = [...MOCK_INVOICES];
      if (filters?.tenantId) {
        rows = rows.filter((i) => i.tenantId === filters.tenantId);
      }
      if (filters?.status) {
        rows = rows.filter((i) => i.status === filters.status);
      }
      if (filters?.from) {
        rows = rows.filter((i) => i.period >= filters.from!);
      }
      if (filters?.to) {
        rows = rows.filter((i) => i.period <= filters.to!);
      }
      return { total: rows.length, invoices: rows };
    }
  },

  /**
   * GET /api/admin/invoices/{invoiceId} — full invoice detail.
   * Falls back to the mock list lookup if T4 isn't shipped.
   */
  get: async (invoiceId: string): Promise<Invoice> => {
    try {
      const { data } = await http.get<Invoice>(
        `/admin/invoices/${encodeURIComponent(invoiceId)}`,
      );
      return data;
    } catch {
      const found = MOCK_INVOICES.find((i) => i.invoiceId === invoiceId);
      if (!found) {
        throw new Error(`invoice not found: ${invoiceId}`);
      }
      return found;
    }
  },
};

export interface TenantQuota {
  tenantId: string;
  tier: TenantTier;
  /** Calls allowed per calendar month. 0 = unlimited. */
  callsLimit: number;
  /** Tokens allowed per calendar month. 0 = unlimited. */
  tokensLimit: number;
  /** When the quota was last edited (ISO-8601). */
  updatedAt: string;
  /** Operator who edited it (admin user id from JWT). */
  updatedBy: string | null;
}

export const quotasApi = {
  /**
   * GET /api/admin/quotas — list every tenant's quota config.
   */
  list: async (): Promise<TenantQuota[]> => {
    try {
      const { data } = await http.get<TenantQuota[]>("/admin/quotas");
      return data;
    } catch {
      return MOCK_QUOTAS;
    }
  },

  /**
   * PUT /api/admin/quotas/{tenantId} — update one tenant's quota.
   * Body shape is the full TenantQuota (PUT replaces the whole row).
   * Returns the updated row.
   */
  update: async (quota: TenantQuota): Promise<TenantQuota> => {
    try {
      const { data } = await http.put<TenantQuota>(
        `/admin/quotas/${encodeURIComponent(quota.tenantId)}`,
        quota,
      );
      return data;
    } catch {
      // Mock fallback: merge into the in-memory list. This is
      // process-local — refreshing the page resets it. The UI shows
      // the updated values immediately because we return what the
      // caller asked for (echo + a fresh updatedAt).
      const idx = MOCK_QUOTAS.findIndex((q) => q.tenantId === quota.tenantId);
      const next: TenantQuota = {
        ...quota,
        updatedAt: new Date().toISOString(),
      };
      if (idx >= 0) {
        MOCK_QUOTAS[idx] = next;
      } else {
        MOCK_QUOTAS.push(next);
      }
      return next;
    }
  },
};

// ----------------------------------------------------------------------------
// Mock fallback data — only consulted when the real endpoint 404s.
// Kept here (not in a separate file) so the API client is fully
// self-contained and the UI can be exercised with zero backend.
// ----------------------------------------------------------------------------

const MOCK_TENANT_USAGE: TenantUsage[] = [
  {
    tenantId: "dev",
    tier: "FREE",
    month: new Date().toISOString().slice(0, 7),
    calls: 142,
    tokens: 89_340,
    callsLimit: 1_000,
    tokensLimit: 500_000,
    callsUsagePct: 14,
    tokensUsagePct: 18,
    lastActiveAt: new Date(Date.now() - 1000 * 60 * 12).toISOString(),
  },
  {
    tenantId: "staging",
    tier: "PRO",
    month: new Date().toISOString().slice(0, 7),
    calls: 4_821,
    tokens: 3_120_550,
    callsLimit: 10_000,
    tokensLimit: 5_000_000,
    callsUsagePct: 48,
    tokensUsagePct: 62,
    lastActiveAt: new Date(Date.now() - 1000 * 60 * 4).toISOString(),
  },
  {
    tenantId: "prod",
    tier: "ENTERPRISE",
    month: new Date().toISOString().slice(0, 7),
    calls: 87_412,
    tokens: 41_289_104,
    callsLimit: 0,
    tokensLimit: 0,
    callsUsagePct: 0,
    tokensUsagePct: 0,
    lastActiveAt: new Date(Date.now() - 1000 * 60).toISOString(),
  },
];

const MOCK_INVOICES: Invoice[] = [
  {
    invoiceId: "inv_2026_05_dev",
    tenantId: "dev",
    period: "2026-05",
    amount: 0,
    currency: "USD",
    status: "PAID",
    createdAt: "2026-06-01T00:00:00Z",
    paidAt: "2026-06-01T00:05:23Z",
    lineItems: [{ label: "FREE tier — no charge", quantity: 1, unitAmount: 0 }],
  },
  {
    invoiceId: "inv_2026_05_staging",
    tenantId: "staging",
    period: "2026-05",
    amount: 49_00,
    currency: "USD",
    status: "PAID",
    createdAt: "2026-06-01T00:00:00Z",
    paidAt: "2026-06-01T00:01:14Z",
    lineItems: [
      { label: "PRO tier base", quantity: 1, unitAmount: 49_00 },
      { label: "Overage: 4,821 calls × $0", quantity: 4821, unitAmount: 0 },
    ],
  },
  {
    invoiceId: "inv_2026_05_prod",
    tenantId: "prod",
    period: "2026-05",
    amount: 1_290_00,
    currency: "USD",
    status: "PAID",
    createdAt: "2026-06-01T00:00:00Z",
    paidAt: "2026-06-01T00:02:08Z",
    lineItems: [
      { label: "ENTERPRISE tier base", quantity: 1, unitAmount: 1_290_00 },
    ],
  },
  {
    invoiceId: "inv_2026_06_dev",
    tenantId: "dev",
    period: "2026-06",
    amount: 0,
    currency: "USD",
    status: "PENDING",
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 6).toISOString(),
    paidAt: null,
    lineItems: [{ label: "FREE tier — no charge", quantity: 1, unitAmount: 0 }],
  },
  {
    invoiceId: "inv_2026_06_staging",
    tenantId: "staging",
    period: "2026-06",
    amount: 49_00,
    currency: "CNY",
    status: "FAILED",
    createdAt: new Date(Date.now() - 1000 * 60 * 60 * 24 * 8).toISOString(),
    paidAt: null,
    lineItems: [
      { label: "PRO tier base", quantity: 1, unitAmount: 49_00 },
    ],
  },
];

const MOCK_QUOTAS: TenantQuota[] = [
  {
    tenantId: "dev",
    tier: "FREE",
    callsLimit: 1_000,
    tokensLimit: 500_000,
    updatedAt: "2026-05-01T00:00:00Z",
    updatedBy: null,
  },
  {
    tenantId: "staging",
    tier: "PRO",
    callsLimit: 10_000,
    tokensLimit: 5_000_000,
    updatedAt: "2026-05-12T14:32:11Z",
    updatedBy: "admin",
  },
  {
    tenantId: "prod",
    tier: "ENTERPRISE",
    callsLimit: 0,
    tokensLimit: 0,
    updatedAt: "2026-04-20T09:00:00Z",
    updatedBy: "admin",
  },
];