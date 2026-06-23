import { Routes, Route, Link } from "react-router-dom";
import { HomePage } from "@/pages/HomePage";
import { IngestPage } from "@/pages/IngestPage";
import { PreviewPage } from "@/pages/PreviewPage";
import { VersionsPage } from "@/pages/VersionsPage";
import { Button } from "@/components/ui/button";

/**
 * Root component — Phase 36-T1 scaffold + T2a /ingest + T2b /preview + T2c /versions.
 *
 * Routing:
 *   /                  → HomePage (Phase 36 dashboard placeholder)
 *   /ingest            → IngestPage (drag-drop PDF → /preview/{jobId})
 *   /preview/:jobId    → PreviewPage (chunk pipeline counters)
 *   /versions          → VersionsPage (KB version metadata diff)
 */
export default function App() {
  return (
    <div className="min-h-screen bg-background text-foreground">
      <header className="border-b">
        <div className="container flex h-14 items-center justify-between">
          <Link to="/" className="text-lg font-semibold">
            RAG Admin
          </Link>
          <nav className="flex items-center gap-2">
            <Button asChild variant="ghost" size="sm">
              <Link to="/">Home</Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/ingest">/ingest</Link>
            </Button>
            <Button asChild variant="ghost" size="sm">
              <Link to="/versions">/versions</Link>
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
        </Routes>
      </main>
    </div>
  );
}