/**
 * MSW handlers — Phase 40-T5 admin API mocks.
 *
 * These return the SAME canned data on every request — there is no
 * mutable state to manage. If we later add server-side state (e.g.
 * an "edit quota" flow that mutates the canonical quota list), swap
 * these for a small in-memory store. For now we just want pages to
 * render and tests to assert against a deterministic shape.
 *
 * Routes mirror the API client in src/api/client.ts:
 *   GET  /api/admin/usage
 *   GET  /api/admin/invoices
 *   GET  /api/admin/invoices/{invoiceId}
 *   GET  /api/admin/quotas
 *   PUT  /api/admin/quotas/{tenantId}
 */
import { http, HttpResponse } from "msw";

const currentMonth = new Date().toISOString().slice(0, 7);

export const mockTenants = [
  {
    tenantId: "dev",
    tier: "FREE",
    month: currentMonth,
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
    month: currentMonth,
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
    month: currentMonth,
    calls: 87_412,
    tokens: 41_289_104,
    callsLimit: 0,
    tokensLimit: 0,
    callsUsagePct: 0,
    tokensUsagePct: 0,
    lastActiveAt: new Date(Date.now() - 1000 * 60).toISOString(),
  },
];

export const mockInvoices = [
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

export const mockQuotas = [
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

export const handlers = [
  // GET /api/admin/usage?month=YYYY-MM
  http.get("/api/admin/usage", ({ request }) => {
    const url = new URL(request.url);
    const month = url.searchParams.get("month") ?? currentMonth;
    return HttpResponse.json({ month, tenants: mockTenants });
  }),

  // GET /api/admin/invoices?tenantId=&from=&to=&status=
  http.get("/api/admin/invoices", ({ request }) => {
    const url = new URL(request.url);
    const tenantId = url.searchParams.get("tenantId");
    const from = url.searchParams.get("from");
    const to = url.searchParams.get("to");
    const status = url.searchParams.get("status");
    let rows = [...mockInvoices];
    if (tenantId) rows = rows.filter((i) => i.tenantId === tenantId);
    if (status) rows = rows.filter((i) => i.status === status);
    if (from) rows = rows.filter((i) => i.period >= from);
    if (to) rows = rows.filter((i) => i.period <= to);
    return HttpResponse.json({ total: rows.length, invoices: rows });
  }),

  // GET /api/admin/invoices/{invoiceId}
  http.get("/api/admin/invoices/:invoiceId", ({ params }) => {
    const found = mockInvoices.find((i) => i.invoiceId === params.invoiceId);
    if (!found) {
      return HttpResponse.json(
        { error: `invoice not found: ${params.invoiceId}` },
        { status: 404 },
      );
    }
    return HttpResponse.json(found);
  }),

  // GET /api/admin/quotas
  http.get("/api/admin/quotas", () => {
    return HttpResponse.json(mockQuotas);
  }),

  // PUT /api/admin/quotas/{tenantId} — echo back the request body
  http.put("/api/admin/quotas/:tenantId", async ({ request, params }) => {
    const body = (await request.json()) as {
      tier: string;
      callsLimit: number;
      tokensLimit: number;
    };
    return HttpResponse.json({
      tenantId: params.tenantId,
      tier: body.tier,
      callsLimit: body.callsLimit,
      tokensLimit: body.tokensLimit,
      updatedAt: new Date().toISOString(),
      updatedBy: "admin",
    });
  }),
];
