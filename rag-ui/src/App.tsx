import { Routes, Route, Link } from "react-router-dom";
import { HomePage } from "@/pages/HomePage";
import { IngestPage } from "@/pages/IngestPage";
import { PreviewPage } from "@/pages/PreviewPage";
import { VersionsPage } from "@/pages/VersionsPage";
import { GradualPage } from "@/pages/GradualPage";
import { RateLimitPage } from "@/pages/RateLimitPage";
import { UsagePage } from "@/pages/UsagePage";
import { InvoicesPage } from "@/pages/InvoicesPage";
import { QuotasPage } from "@/pages/QuotasPage";
import { Button } from "@/components/ui/button";

/**
 * Root component — Phase 36 scaffold + T2a /ingest + T2b /preview
 * + T2c /versions + T3 /gradual + /rate-limit + Phase 40-T5
 * /usage + /invoices + /quotas.
 *
 * Routing:
 *   /              → HomePage (Phase 36 dashboard placeholder)
 *   /ingest        → IngestPage (drag-drop PDF → /preview/{jobId})
 *   /preview/:jobId → PreviewPage (chunk pipeline counters)
 *   /versions      → VersionsPage (KB version metadata diff)
 *   /gradual       → GradualPage (灰度发布 — version activate/rollback)
 *   /rate-limit    → RateLimitPage (tenant rate limit stub)
 *   /usage         → UsagePage (Phase 40-T5 tenant usage dashboard)
 *   /invoices      → InvoicesPage (Phase 40-T5 invoice list + detail)
 *   /quotas        → QuotasPage (Phase 40-T5 admin quota editor)
 */
export default function App() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b">
        <div className="container flex h-14 items-center justify-between">
          <Link to="/" className="text-lg font-semibold">
            RAG Admin
          </Link>
          <nav className="flex flex-wrap items-center gap-2">
            <Button asChild variant="ghost" size="sm">
              <Link to="/">Home</Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/ingest">/ingest</Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/versions">/versions</Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/gradual">/gradual</Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/rate-limit">/rate-limit</Link>
            </Button>
            <Button asChild variant="ghost" size="sm" data-testid="nav-usage">
              <Link to="/usage">/usage</Link>
            </Button>
            <Button asChild variant="ghost" size="sm" data-testid="nav-invoices">
              <Link to="/invoices">/invoices</Link>
            </Button>
            <Button asChild variant="ghost" size="sm" data-testid="nav-quotas">
              <Link to="/quotas">/quotas</Link>
            </Button>
          </nav>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
          <Route path="/ingest" element={<IngestPage />} />
          <Route path="/preview/:jobId" element={<PreviewPage />} />
          <Route path="/versions" element={<VersionsPage />} />
          <Route path="/gradual" element={<GradualPage />} />
          <Route path="/rate-limit" element={<RateLimitPage />} />
          <Route path="/usage" element={<UsagePage />} />
          <Route path="/invoices" element={<InvoicesPage />} />
          <Route path="/quotas" element={<QuotasPage />} />
        </Routes>
      </main>
    </div>
  );
}
