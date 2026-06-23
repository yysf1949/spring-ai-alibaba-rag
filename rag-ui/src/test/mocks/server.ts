/**
 * MSW server — Phase 40-T5.
 *
 * One singleton server wired with ./handlers.ts. Started/stopped by
 * src/test/setup.ts (via beforeAll/afterAll) and re-used across tests.
 */
import { setupServer } from "msw/node";
import { handlers } from "./handlers";

export const server = setupServer(...handlers);
