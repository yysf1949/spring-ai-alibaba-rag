import { Card, CardContent, CardDescription, CardHeader, CardTitle } from "@/components/ui/card";

/**
 * HomePage — Phase 36-T1 placeholder.
 *
 * Verifies that:
 *  1. Vite + React 18 + Tailwind + shadcn/ui are all wired up
 *  2. The OpenAPI TS client is importable (rendered in dev console)
 *
 * T2 will replace this with the actual ingest dashboard.
 */
export function HomePage() {
  return (
    <div className="container py-10">
      <h1 className="text-3xl font-bold tracking-tight">RAG Admin</h1>
      <p className="text-muted-foreground mt-2">
        Phase 36-T1 scaffold. Backend: <code className="rounded bg-muted px-1.5 py-0.5 text-sm">rag-app</code> on Spring Boot 3.3.
      </p>

      <div className="mt-8 grid gap-4 md:grid-cols-2">
        <Card>
          <CardHeader>
            <CardTitle>OpenAPI client</CardTitle>
            <CardDescription>Generated from <code>docs/openapi/openapi.json</code></CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              Use <code className="rounded bg-muted px-1.5 py-0.5 text-sm">ingestApi.listJobs()</code> from{" "}
              <code className="rounded bg-muted px-1.5 py-0.5 text-sm">@/api/client</code> to list ingest jobs.
            </p>
          </CardContent>
        </Card>

        <Card>
          <CardHeader>
            <CardTitle>Next: Phase 36-T2</CardTitle>
            <CardDescription>Drag-drop upload + preview + version</CardDescription>
          </CardHeader>
          <CardContent>
            <p className="text-sm text-muted-foreground">
              Will mount at <code className="rounded bg-muted px-1.5 py-0.5 text-sm">/ingest</code>.
            </p>
          </CardContent>
        </Card>
      </div>
    </div>
  );
}
