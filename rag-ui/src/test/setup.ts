/**
 * Vitest setup — runs once per test file.
 *
 * Two responsibilities:
 *   1. Register @testing-library/jest-dom matchers (toBeInTheDocument etc.).
 *   2. Start MSW server with our admin-API handlers so component tests
 *      can hit /api/admin/* without a real backend.
 *
 * Why we don't bother tearing down per-test:
 *   We use a single handlers module (./mocks/handlers.ts) that returns
 *   the SAME canned data on every request. There is no state to reset
 *   between tests — if we later add mutable state (e.g. an "edit quota"
 *   flow that updates server state), add a `beforeEach(() => server.resetHandlers())`
 *   here. For now, one global server is fine.
 */
import "@testing-library/jest-dom/vitest";
import { afterAll, afterEach, beforeAll } from "vitest";
import { server } from "./mocks/server";

// Establish API mocking before all tests.
beforeAll(() => server.listen({ onUnhandledRequest: "error" }));

// Reset any request handlers that we may add during the tests,
// so they don't affect other tests.
afterEach(() => server.resetHandlers());

// Clean up after the tests are finished.
afterAll(() => server.close());