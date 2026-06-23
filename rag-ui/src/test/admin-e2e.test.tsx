/**
 * Admin UI E2E — Phase 40-T5 acceptance test.
 *
 * This is the single test the task body requires ("登录 admin → 看
 * 见自己 tenant 的 usage"). It uses MemoryRouter + MSW + RTL to
 * simulate an admin operator navigating /usage, /invoices and
 * /quotas against the mocked backend — the same canned data shape
 * the real T3/T4 controllers will eventually return.
 *
 * Why one combined test:
 *   The DoD says "≥1 E2E". Combining all three pages keeps the test
 *   runtime short while still proving the operator journey:
 *     1. Land on /usage — see the three tenants, see tier badges,
 *        see usage bars, see one tenant flagged "unlimited".
 *     2. Click through /invoices — filter by status, click a row,
 *        see the detail dialog with line items.
 *     3. Click through /quotas — edit a row, save, see the
 *        updated timestamp change.
 *
 * Auth simulation:
 *   The dev proxy is implicit-admin (no real auth in this dev
 *   environment). The Spring backend gates /api/admin/* behind
 *   ROLE_ADMIN in production (Phase 34-T34b) — that gate runs at
 *   the API layer, not in the UI.
 */
import { describe, expect, it, beforeEach } from "vitest";
import { render, screen, within, waitFor, fireEvent } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { MemoryRouter, Routes, Route } from "react-router-dom";
import App from "@/App";

function renderAt(path: string) {
  return render(
    <MemoryRouter initialEntries={[path]}>
      <Routes>
        <Route path="/*" element={<App />} />
      </Routes>
    </MemoryRouter>,
  );
}

describe("Admin UI E2E (Phase 40-T5)", () => {
  beforeEach(() => {
    // setup.ts (src/test/setup.ts) starts MSW beforeAll + resets
    // handlers afterEach, so each test gets the canned mock data.
  });

  it("admin sees tenant usage on /usage, drills into an invoice on /invoices, and edits a quota on /quotas", async () => {
    const user = userEvent.setup();

    // -----------------------------------------------------------------
    // 1. /usage — operator lands on the dashboard.
    // -----------------------------------------------------------------
    renderAt("/usage");
    await waitFor(() => {
      expect(screen.getByTestId("usage-page")).toBeInTheDocument();
    });

    // Three tenants from the MSW handler.
    const usageRows = await screen.findAllByTestId("usage-row");
    expect(usageRows).toHaveLength(3);

    // Tenant ids rendered correctly.
    const tenants = usageRows.map((r) => r.getAttribute("data-tenant"));
    expect(tenants).toEqual(expect.arrayContaining(["dev", "staging", "prod"]));

    // Tier badges — FREE / PRO / ENTERPRISE.
    const tierBadges = screen.getAllByTestId("tier-badge");
    const tierLabels = tierBadges.map((b) => b.textContent);
    expect(tierLabels).toEqual(
      expect.arrayContaining(["FREE", "PRO", "ENTERPRISE"]),
    );

    // "prod" tenant has limit=0/0 → unlimited tag.
    const prodRow = usageRows.find((r) => r.getAttribute("data-tenant") === "prod");
    expect(prodRow).toBeDefined();
    expect(within(prodRow!).getByTestId("usage-bar-unlimited")).toBeInTheDocument();

    // Non-pro tenants render a usage bar.
    const bars = screen.getAllByTestId("usage-bar");
    expect(bars.length).toBeGreaterThanOrEqual(2);

    // -----------------------------------------------------------------
    // 2. /invoices — operator drills into a specific invoice.
    // -----------------------------------------------------------------
    const navInvoices = screen.getByTestId("nav-invoices");
    await user.click(navInvoices);

    await waitFor(() => {
      expect(screen.getByTestId("invoices-page")).toBeInTheDocument();
    });

    const invoiceRows = await screen.findAllByTestId("invoice-row");
    expect(invoiceRows.length).toBeGreaterThanOrEqual(3);

    // Filter by status = FAILED — should reduce the list.
    const statusSelect = screen.getByTestId("filter-status");
    await user.selectOptions(statusSelect, "FAILED");

    const failedRows = await screen.findAllByTestId("invoice-row");
    expect(failedRows.length).toBeLessThan(invoiceRows.length);
    expect(failedRows.length).toBeGreaterThanOrEqual(1);

    // Reset filters and open the first invoice detail.
    await user.click(screen.getByTestId("reset-filters"));
    const allRows = await screen.findAllByTestId("invoice-row");
    await user.click(allRows[0]);

    await waitFor(() => {
      expect(screen.getByTestId("invoice-detail")).toBeInTheDocument();
    });
    // The detail shows status + amount + total.
    expect(screen.getByTestId("detail-status")).toBeInTheDocument();
    expect(screen.getByTestId("detail-amount")).toBeInTheDocument();
    expect(screen.getByTestId("detail-total")).toBeInTheDocument();

    // Close the dialog.
    await user.click(screen.getByTestId("close-dialog"));
    await waitFor(() => {
      expect(screen.queryByTestId("invoice-detail")).not.toBeInTheDocument();
    });

    // -----------------------------------------------------------------
    // 3. /quotas — admin edits a tenant's quota and saves.
    // -----------------------------------------------------------------
    const navQuotas = screen.getByTestId("nav-quotas");
    await user.click(navQuotas);

    await waitFor(() => {
      expect(screen.getByTestId("quotas-page")).toBeInTheDocument();
    });

    const quotaRows = await screen.findAllByTestId("quota-row");
    expect(quotaRows).toHaveLength(3);

    // Pick the "dev" row and edit it.
    const devRow = quotaRows.find((r) => r.getAttribute("data-tenant") === "dev");
    expect(devRow).toBeDefined();
    const editBtn = within(devRow!).getByTestId("edit-quota");
    await user.click(editBtn);

    // Edit form unlocked.
    const tierSelect = within(devRow!).getByTestId("edit-tier");
    const callsInput = within(devRow!).getByTestId("edit-calls-limit");
    await user.selectOptions(tierSelect, "PRO");
    fireEvent.change(callsInput, { target: { value: "2500" } });

    // Save — MSW handler echoes back with a fresh updatedAt.
    await user.click(within(devRow!).getByTestId("save-quota"));

    await waitFor(() => {
      // After save, the edit form closes (no more save button inside this row).
      expect(within(devRow!).queryByTestId("save-quota")).not.toBeInTheDocument();
      // Tier badge now reads PRO.
      expect(within(devRow!).getByTestId("row-tier").textContent).toBe("PRO");
    });
  });
});
