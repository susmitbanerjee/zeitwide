"use client";

import { createZeitwideClient, type ZeitwideClient } from "@/api/client";

/**
 * The typed API client for client components. It calls /api/zw on this origin, which adds the
 * signed-in user's token on the server, so no token is ever readable from JavaScript.
 */
export const browserApi: ZeitwideClient = createZeitwideClient({
  baseUrl: "/api/zw",
  fetch: (input) => globalThis.fetch(input),
});

/** A fresh Idempotency-Key for one create request. */
export function idempotencyKey(): string {
  return crypto.randomUUID();
}
