import { warmUpBackends } from "@/auth/warm-up";

/**
 * Called once per visit by the public pages (see WarmUp). It only wakes our own services, takes
 * no input and is throttled, so it needs no sign-in.
 */
export function POST(): Response {
  warmUpBackends();
  return new Response(null, { status: 204, headers: { "cache-control": "no-store" } });
}
