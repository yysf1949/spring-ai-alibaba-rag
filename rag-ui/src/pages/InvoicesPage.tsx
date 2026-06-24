/**
 * InvoicesPage — Phase 40-T5 /invoices list + detail dialog.
 *
 * Operator flow:
 *   1. Apply optional filters (tenant / from / to / status). All four
 *      filters are AND-ed together — matches the backend's contract.
 *   2. invoicesApi.list(filters) returns the matching rows.
 *   3. Click a row → opens a dialog with the full Invoice record
 *      (line items, paid-at, etc.) via invoicesApi.get(invoiceId).
 *
 * Failure mode:
 *   Same as UsagePage: when T4 ships the real endpoint, the client
 *   switches transparently (see src/api/client.ts: invoicesApi).
 */
import { useEffect, useState } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  invoicesApi,
  type Invoice,
  type InvoiceStatus,
} from "@/api/client";

const STATUS_PALETTE: Record<InvoiceStatus, string> = {
  PENDING: "bg-blue-100 text-blue-800",
  PAID: "bg-emerald-100 text-emerald-900",
  FAILED: "bg-red-100 text-red-900",
  REFUNDED: "bg-amber-100 text-amber-900",
};

const STATUSES: InvoiceStatus[] = ["PENDING", "PAID", "FAILED", "REFUNDED"];

function formatAmount(amount: number, currency: Invoice["currency"]): string {
  const major = amount / 100;
  return `${major.toFixed(2)} ${currency}`;
}

function formatTimestamp(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

interface Filters {
  tenantId: string;
  from: string;
  to: string;
  status: "" | InvoiceStatus;
}

const EMPTY_FILTERS: Filters = { tenantId: "", from: "", to: "", status: "" };

interface DetailDialogProps {
  invoice: Invoice | null;
  onClose: () => void;
}

function DetailDialog({ invoice, onClose }: DetailDialogProps) {
  // Close on Escape.
  useEffect(() => {
    if (!invoice) return;
    const handler = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
    };
    window.addEventListener("keydown", handler);
    return () => window.removeEventListener("keydown", handler);
  }, [invoice, onClose]);

  if (!invoice) return null;

  const total = invoice.lineItems.reduce(
    (acc, li) => acc + li.quantity * li.unitAmount,
    0,
  );

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-black/40"
      data-testid="invoice-detail"
      role="dialog"
      aria-modal="true"
      onClick={onClose}
    >
      <div
        className="w-full max-w-lg rounded-lg bg-background p-6 shadow-lg"
        onClick={(e) => e.stopPropagation()}
      >
        <div className="mb-4 flex items-start justify-between">
          <div>
            <h2 className="text-lg font-semibold">{invoice.invoiceId}</h2>
            <p className="text-sm text-muted-foreground">
              {invoice.tenantId} · {invoice.period}
            </p>
          </div>
          <button
            type="button"
            onClick={onClose}
            className="text-muted-foreground hover:text-foreground"
            data-testid="close-dialog"
            aria-label="Close"
          >
            ×
          </button>
        </div>

        <dl className="mb-4 grid grid-cols-2 gap-2 text-sm">
          <dt className="text-muted-foreground">Status</dt>
          <dd>
            <span
              className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${STATUS_PALETTE[invoice.status]}`}
              data-testid="detail-status"
            >
              {invoice.status}
            </span>
          </dd>
          <dt className="text-muted-foreground">Amount</dt>
          <dd className="tabular-nums" data-testid="detail-amount">
            {formatAmount(invoice.amount, invoice.currency)}
          </dd>
          <dt className="text-muted-foreground">Created</dt>
          <dd className="text-xs">{formatTimestamp(invoice.createdAt)}</dd>
          <dt className="text-muted-foreground">Paid</dt>
          <dd className="text-xs">{formatTimestamp(invoice.paidAt)}</dd>
        </dl>

        <h3 className="mb-2 text-sm font-medium">Line items</h3>
        <table className="w-full text-sm">
          <thead className="text-left text-muted-foreground">
            <tr>
              <th className="py-1">Label</th>
              <th className="py-1 text-right">Qty</th>
              <th className="py-1 text-right">Unit</th>
              <th className="py-1 text-right">Subtotal</th>
            </tr>
          </thead>
          <tbody>
            {invoice.lineItems.map((li, i) => (
              <tr key={i} className="border-t">
                <td className="py-1">{li.label}</td>
                <td className="py-1 text-right tabular-nums">
                  {li.quantity.toLocaleString()}
                </td>
                <td className="py-1 text-right tabular-nums">
                  {(li.unitAmount / 100).toFixed(2)}
                </td>
                <td className="py-1 text-right tabular-nums">
                  {((li.quantity * li.unitAmount) / 100).toFixed(2)}
                </td>
              </tr>
            ))}
            <tr className="border-t font-medium">
              <td className="py-1">Total</td>
              <td />
              <td />
              <td className="py-1 text-right tabular-nums" data-testid="detail-total">
                {(total / 100).toFixed(2)} {invoice.currency}
              </td>
            </tr>
          </tbody>
        </table>

        <div className="mt-6 flex justify-end">
          <Button variant="outline" onClick={onClose}>
            Close
          </Button>
        </div>
      </div>
    </div>
  );
}

export function InvoicesPage() {
  const [filters, setFilters] = useState<Filters>(EMPTY_FILTERS);
  const [invoices, setInvoices] = useState<Invoice[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [detail, setDetail] = useState<Invoice | null>(null);
  const [detailError, setDetailError] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    setError(null);
    const params: Parameters<typeof invoicesApi.list>[0] = {};
    if (filters.tenantId) params.tenantId = filters.tenantId;
    if (filters.from) params.from = filters.from;
    if (filters.to) params.to = filters.to;
    if (filters.status) params.status = filters.status;
    invoicesApi
      .list(params)
      .then((resp) => {
        if (!cancelled) setInvoices(resp.invoices);
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setInvoices(null);
        setError(err instanceof Error ? err.message : String(err));
      });
    return () => {
      cancelled = true;
    };
  }, [filters]);

  const onOpen = async (invoiceId: string) => {
    setDetailError(null);
    try {
      const inv = await invoicesApi.get(invoiceId);
      setDetail(inv);
    } catch (err: unknown) {
      setDetail(null);
      setDetailError(err instanceof Error ? err.message : String(err));
    }
  };

  return (
    <div className="container py-6">
      <Card data-testid="invoices-page">
        <CardHeader>
          <CardTitle>/invoices — 账单</CardTitle>
          <CardDescription>
            按 tenant / 时间段 / 状态过滤；点击查看 line item 详情
          </CardDescription>
        </CardHeader>
        <CardContent>
          <div className="mb-4 grid grid-cols-1 gap-2 sm:grid-cols-4">
            <input
              type="text"
              placeholder="tenantId"
              value={filters.tenantId}
              onChange={(e) =>
                setFilters({ ...filters, tenantId: e.target.value })
              }
              className="rounded-md border border-input bg-background px-3 py-1 text-sm"
              data-testid="filter-tenant"
            />
            <input
              type="month"
              placeholder="from"
              value={filters.from}
              onChange={(e) => setFilters({ ...filters, from: e.target.value })}
              className="rounded-md border border-input bg-background px-3 py-1 text-sm"
              data-testid="filter-from"
            />
            <input
              type="month"
              placeholder="to"
              value={filters.to}
              onChange={(e) => setFilters({ ...filters, to: e.target.value })}
              className="rounded-md border border-input bg-background px-3 py-1 text-sm"
              data-testid="filter-to"
            />
            <select
              value={filters.status}
              onChange={(e) =>
                setFilters({
                  ...filters,
                  status: e.target.value as Filters["status"],
                })
              }
              className="rounded-md border border-input bg-background px-3 py-1 text-sm"
              data-testid="filter-status"
            >
              <option value="">All statuses</option>
              {STATUSES.map((s) => (
                <option key={s} value={s}>
                  {s}
                </option>
              ))}
            </select>
          </div>

          <div className="mb-3">
            <Button
              variant="outline"
              size="sm"
              onClick={() => setFilters(EMPTY_FILTERS)}
              data-testid="reset-filters"
            >
              清空过滤
            </Button>
          </div>

          {error && (
            <p className="text-sm text-destructive" data-testid="error">
              {error}
            </p>
          )}
          {detailError && (
            <p className="text-sm text-destructive" data-testid="detail-error">
              {detailError}
            </p>
          )}

          {invoices && invoices.length === 0 && (
            <p className="text-sm text-muted-foreground" data-testid="empty">
              没有匹配的 invoice
            </p>
          )}
          {invoices && invoices.length > 0 && (
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="px-4 py-2 font-medium">Invoice ID</th>
                  <th className="px-4 py-2 font-medium">Tenant</th>
                  <th className="px-4 py-2 font-medium">Period</th>
                  <th className="px-4 py-2 font-medium">Amount</th>
                  <th className="px-4 py-2 font-medium">Status</th>
                  <th className="px-4 py-2 font-medium">Created</th>
                </tr>
              </thead>
              <tbody>
                {invoices.map((inv) => (
                  <tr
                    key={inv.invoiceId}
                    className="cursor-pointer border-t hover:bg-muted/50"
                    onClick={() => onOpen(inv.invoiceId)}
                    data-testid="invoice-row"
                    data-invoice-id={inv.invoiceId}
                  >
                    <td className="px-4 py-2 font-mono text-xs">
                      {inv.invoiceId}
                    </td>
                    <td className="px-4 py-2">{inv.tenantId}</td>
                    <td className="px-4 py-2">{inv.period}</td>
                    <td className="px-4 py-2 tabular-nums">
                      {formatAmount(inv.amount, inv.currency)}
                    </td>
                    <td className="px-4 py-2">
                      <span
                        className={`inline-flex items-center rounded-md px-2 py-0.5 text-xs font-medium ${STATUS_PALETTE[inv.status]}`}
                      >
                        {inv.status}
                      </span>
                    </td>
                    <td className="px-4 py-2 text-xs text-muted-foreground">
                      {formatTimestamp(inv.createdAt)}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>

      <DetailDialog invoice={detail} onClose={() => setDetail(null)} />
    </div>
  );
}
