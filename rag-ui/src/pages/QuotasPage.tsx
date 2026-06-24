/**
 * QuotasPage — Phase 40-T5 /quotas admin editor.
 *
 * Operator flow:
 *   1. quotasApi.list() loads every tenant's quota config (tier +
 *      callsLimit + tokensLimit + audit metadata).
 *   2. The operator picks a tenant → edit row unlocks a form:
 *        - Tier dropdown (FREE / PRO / ENTERPRISE)
 *        - Calls limit (number; 0 = unlimited)
 *        - Tokens limit (number; 0 = unlimited)
 *      Save → quotasApi.update(quota) → row's updatedAt/updatedBy
 *      refreshes. Cancel reverts local state.
 *
 * Failure mode:
 *   Same as UsagePage — when T3 ships the real endpoint the client
 *   stops using the mock and real data shows up automatically.
 *
 * Permission note:
 *   The body says this is an admin-only page. The backend gates
 *   /api/admin/* behind ROLE_ADMIN (see Phase 34 T34b). The UI does
 *   not re-implement auth — the proxy + Spring Security block
 *   unauthorised callers before they reach here. We do *not* show a
 *   login screen because the dev story runs against a trusted
 *   environment (Phase 36+T5 E2E uses msw + an implicit admin).
 */
import { useEffect, useMemo, useState } from "react";
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";
import { Button } from "@/components/ui/button";
import {
  quotasApi,
  type TenantQuota,
  type TenantTier,
} from "@/api/client";

const TIERS: TenantTier[] = ["FREE", "PRO", "ENTERPRISE"];

type LoadState =
  | { kind: "loading" }
  | { kind: "ok"; quotas: TenantQuota[] }
  | { kind: "error"; message: string };

function formatTimestamp(iso: string | null): string {
  if (!iso) return "—";
  const d = new Date(iso);
  if (Number.isNaN(d.getTime())) return iso;
  return d.toLocaleString();
}

interface EditState {
  tier: TenantTier;
  callsLimit: string;
  tokensLimit: string;
}

function rowToEdit(row: TenantQuota): EditState {
  return {
    tier: row.tier,
    callsLimit: row.callsLimit === 0 ? "" : String(row.callsLimit),
    tokensLimit: row.tokensLimit === 0 ? "" : String(row.tokensLimit),
  };
}

function editToRow(tenantId: string, edit: EditState): TenantQuota {
  const callsLimit = edit.callsLimit.trim() === "" ? 0 : Number(edit.callsLimit);
  const tokensLimit =
    edit.tokensLimit.trim() === "" ? 0 : Number(edit.tokensLimit);
  return {
    tenantId,
    tier: edit.tier,
    callsLimit: Number.isFinite(callsLimit) ? callsLimit : 0,
    tokensLimit: Number.isFinite(tokensLimit) ? tokensLimit : 0,
    updatedAt: new Date().toISOString(),
    updatedBy: "admin",
  };
}

interface QuotaRowProps {
  row: TenantQuota;
  editing: boolean;
  edit: EditState;
  saving: boolean;
  onEdit: () => void;
  onCancel: () => void;
  onSave: () => void;
  onChange: (edit: EditState) => void;
}

function QuotaRow({
  row,
  editing,
  edit,
  saving,
  onEdit,
  onCancel,
  onSave,
  onChange,
}: QuotaRowProps) {
  return (
    <tr className="border-t" data-testid="quota-row" data-tenant={row.tenantId}>
      <td className="px-4 py-2 font-medium">{row.tenantId}</td>
      <td className="px-4 py-2">
        {editing ? (
          <select
            value={edit.tier}
            onChange={(e) => onChange({ ...edit, tier: e.target.value as TenantTier })}
            className="rounded-md border border-input bg-background px-2 py-1 text-sm"
            data-testid="edit-tier"
          >
            {TIERS.map((t) => (
              <option key={t} value={t}>
                {t}
              </option>
            ))}
          </select>
        ) : (
          <span
            className="inline-flex items-center rounded-md bg-muted px-2 py-0.5 text-xs font-medium"
            data-testid="row-tier"
          >
            {row.tier}
          </span>
        )}
      </td>
      <td className="px-4 py-2 tabular-nums">
        {editing ? (
          <input
            type="number"
            min={0}
            placeholder="0 = unlimited"
            value={edit.callsLimit}
            onChange={(e) => onChange({ ...edit, callsLimit: e.target.value })}
            className="w-32 rounded-md border border-input bg-background px-2 py-1 text-sm"
            data-testid="edit-calls-limit"
          />
        ) : row.callsLimit === 0 ? (
          <span className="text-muted-foreground">unlimited</span>
        ) : (
          row.callsLimit.toLocaleString()
        )}
      </td>
      <td className="px-4 py-2 tabular-nums">
        {editing ? (
          <input
            type="number"
            min={0}
            placeholder="0 = unlimited"
            value={edit.tokensLimit}
            onChange={(e) => onChange({ ...edit, tokensLimit: e.target.value })}
            className="w-32 rounded-md border border-input bg-background px-2 py-1 text-sm"
            data-testid="edit-tokens-limit"
          />
        ) : row.tokensLimit === 0 ? (
          <span className="text-muted-foreground">unlimited</span>
        ) : (
          row.tokensLimit.toLocaleString()
        )}
      </td>
      <td className="px-4 py-2 text-xs text-muted-foreground">
        {formatTimestamp(row.updatedAt)}
        {row.updatedBy ? ` · ${row.updatedBy}` : ""}
      </td>
      <td className="px-4 py-2">
        {editing ? (
          <div className="flex gap-2">
            <Button
              size="sm"
              onClick={onSave}
              disabled={saving}
              data-testid="save-quota"
            >
              {saving ? "saving…" : "Save"}
            </Button>
            <Button
              size="sm"
              variant="outline"
              onClick={onCancel}
              disabled={saving}
              data-testid="cancel-edit"
            >
              Cancel
            </Button>
          </div>
        ) : (
          <Button
            size="sm"
            variant="outline"
            onClick={onEdit}
            data-testid="edit-quota"
          >
            Edit
          </Button>
        )}
      </td>
    </tr>
  );
}

export function QuotasPage() {
  const [state, setState] = useState<LoadState>({ kind: "loading" });
  const [editingTenant, setEditingTenant] = useState<string | null>(null);
  const [editDraft, setEditDraft] = useState<EditState | null>(null);
  const [saving, setSaving] = useState(false);
  const [saveError, setSaveError] = useState<string | null>(null);

  // Initial load.
  useEffect(() => {
    let cancelled = false;
    quotasApi
      .list()
      .then((quotas) => {
        if (!cancelled) setState({ kind: "ok", quotas });
      })
      .catch((err: unknown) => {
        if (cancelled) return;
        setState({
          kind: "error",
          message: err instanceof Error ? err.message : String(err),
        });
      });
    return () => {
      cancelled = true;
    };
  }, []);

  const rows = useMemo(() => {
    if (state.kind !== "ok") return [];
    return state.quotas;
  }, [state]);

  const beginEdit = (row: TenantQuota) => {
    setEditingTenant(row.tenantId);
    setEditDraft(rowToEdit(row));
    setSaveError(null);
  };

  const cancelEdit = () => {
    setEditingTenant(null);
    setEditDraft(null);
    setSaveError(null);
  };

  const saveEdit = async () => {
    if (!editingTenant || !editDraft) return;
    setSaving(true);
    setSaveError(null);
    try {
      const next = editToRow(editingTenant, editDraft);
      const updated = await quotasApi.update(next);
      if (state.kind === "ok") {
        const merged = state.quotas.map((q) =>
          q.tenantId === updated.tenantId ? updated : q,
        );
        setState({ kind: "ok", quotas: merged });
      }
      setEditingTenant(null);
      setEditDraft(null);
    } catch (err: unknown) {
      setSaveError(err instanceof Error ? err.message : String(err));
    } finally {
      setSaving(false);
    }
  };

  return (
    <div className="container py-6">
      <Card data-testid="quotas-page">
        <CardHeader>
          <CardTitle>/quotas — tenant 配额编辑</CardTitle>
          <CardDescription>
            修改 tier + calls/tokens limit，立即生效。0 表示 unlimited
          </CardDescription>
        </CardHeader>
        <CardContent>
          {state.kind === "loading" && (
            <p className="text-sm text-muted-foreground" data-testid="loading">
              loading…
            </p>
          )}
          {state.kind === "error" && (
            <p className="text-sm text-destructive" data-testid="error">
              {state.message}
            </p>
          )}
          {saveError && (
            <p className="text-sm text-destructive" data-testid="save-error">
              {saveError}
            </p>
          )}
          {state.kind === "ok" && (
            <table className="w-full text-sm">
              <thead className="text-left text-muted-foreground">
                <tr>
                  <th className="px-4 py-2 font-medium">Tenant</th>
                  <th className="px-4 py-2 font-medium">Tier</th>
                  <th className="px-4 py-2 font-medium">Calls limit</th>
                  <th className="px-4 py-2 font-medium">Tokens limit</th>
                  <th className="px-4 py-2 font-medium">Updated</th>
                  <th className="px-4 py-2 font-medium">Action</th>
                </tr>
              </thead>
              <tbody>
                {rows.map((row) => (
                  <QuotaRow
                    key={row.tenantId}
                    row={row}
                    editing={editingTenant === row.tenantId}
                    edit={
                      editingTenant === row.tenantId && editDraft
                        ? editDraft
                        : rowToEdit(row)
                    }
                    saving={saving && editingTenant === row.tenantId}
                    onEdit={() => beginEdit(row)}
                    onCancel={cancelEdit}
                    onSave={saveEdit}
                    onChange={(e) => setEditDraft(e)}
                  />
                ))}
              </tbody>
            </table>
          )}
        </CardContent>
      </Card>
    </div>
  );
}
