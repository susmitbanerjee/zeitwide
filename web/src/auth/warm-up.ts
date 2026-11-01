import "server-only";

import { after } from "next/server";

import { env } from "@/lib/env";

const THROTTLE_MS = 3 * 60_000;
let lastWarmUp = 0;

/**
 * Wakes the API ahead of the first call. It scales to zero on Cloud Run and takes 20 to 30
 * seconds to start, so the earlier someone is seen on the site, the more likely it is ready by
 * the time they sign in and the app asks it something. Runs after the response has been sent, at
 * most once every few minutes per instance, and an error is nobody's business.
 */
export function warmUpBackends(now: number = Date.now()): void {
  if (now - lastWarmUp < THROTTLE_MS) {
    return;
  }
  lastWarmUp = now;
  after(async () => {
    await fetch(`${env.apiUrl}/actuator/health`, {
      cache: "no-store",
      signal: AbortSignal.timeout(120_000),
    }).catch(() => undefined);
  });
}
