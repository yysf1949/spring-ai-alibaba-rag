import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";
import path from "node:path";

/**
 * Vitest config for Phase 40-T5 E2E harness.
 *
 * We reuse the same `@` alias + React plugin as Vite so component
 * tests resolve the same way the dev server does. jsdom is required
 * because we render React DOM with @testing-library/react.
 *
 * Setup file (./src/test/setup.ts) wires @testing-library/jest-dom
 * matchers (toBeInTheDocument etc.) and starts MSW before each test.
 *
 * Environment choice:
 *   We use happy-dom instead of jsdom because jsdom 27 transitively
 *   imports @csstools/css-calc which is ESM-only and triggers
 *   ERR_REQUIRE_ESM when vitest 4 forks worker processes. happy-dom
 *   is a lighter, fully-ESM drop-in for the DOM bits we exercise
 *   (render, click, change). We do NOT exercise CSS layout, so the
 *   happy-dom trade-offs are acceptable here.
 */
export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  test: {
    environment: "happy-dom",
    globals: true,
    setupFiles: ["./src/test/setup.ts"],
    css: false,
    include: ["src/**/*.{test,spec}.{ts,tsx}"],
  },
});