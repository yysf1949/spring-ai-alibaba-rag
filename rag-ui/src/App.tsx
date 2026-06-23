import { Routes, Route, Link } from "react-router-dom";
import { HomePage } from "@/pages/HomePage";
import { Button } from "@/components/ui/button";

/**
 * Root component — Phase 36-T1 scaffold.
 *
 * Routing placeholder: only HomePage for now. T2 will add `/ingest`,
 * `/versions`, `/gradual-rollout` etc. See Phase 36 plan.
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
          </nav>
        </div>
      </header>
      <main>
        <Routes>
          <Route path="/" element={<HomePage />} />
        </Routes>
      </main>
    </div>
  );
}
