/**
 * Type stub for the openapi-typescript generated output.
 *
 * The real `schema.d.ts` is produced by `npm run openapi:gen`
 * (which invokes `openapi-typescript` against docs/openapi/openapi.json).
 * That codegen step is part of the local dev workflow, not the
 * T1 scaffold's build, so we ship a hand-typed stub to keep
 * `tsc --noEmit` and `vite build` green before the user has run
 * the codegen once.
 *
 * If you regenerate the real schema, delete this file — the codegen
 * will create a fresh `schema.d.ts` next to it.
 */

export interface paths {
  "/api/ingest": {
    post: {
      requestBody?: { content: { "application/json": { tenantId?: string; sourceUri: string } } };
      responses: { 202: { content: { "application/json": unknown } } };
    };
  };
  "/api/ingest/{jobId}": {
    get: {
      parameters: { path: { jobId: string } };
      responses: { 200: { content: { "application/json": unknown } } };
    };
  };
  "/api/agent/kb-versions/{kbId}": {
    get: {
      parameters: { path: { kbId: string } };
      responses: {
        200: {
          content: {
            "application/json": {
              kbId: string;
              versions: Array<{
                versionId: number;
                status: "DRAFT" | "STAGING" | "ACTIVE" | "DEPRECATED";
                createdAt: string;
                publishedAt: string | null;
                docCount: number;
                sourceLabel: string | null;
              }>;
            };
          };
        };
      };
    };
  };
}
