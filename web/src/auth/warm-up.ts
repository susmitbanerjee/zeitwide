import "server-only";

import { after } from "next/server";

import { env } from "@/lib/env";

const issuer = process.env.AUTH_KEYCLOAK_ISSUER ?? "http://localhost:8180/realms/zeitwide";

/**
 * Wakes Keycloak and the API while the visitor is still choosing how to sign in. Both scale to
 * zero on Cloud Run and take 20 to 30 seconds to start, so by the time a button is clicked they
 * are usually ready. Runs after the page has been sent; an error is nobody's business.
 */
export function warmUpBackends(): void {
  after(async () => {
    const ping = (url: string) =>
      fetch(url, { cache: "no-store", signal: AbortSignal.timeout(60_000) }).catch(() => undefined);
    await Promise.all([
      ping(`${issuer}/.well-known/openid-configuration`),
      ping(`${env.apiUrl}/actuator/health`),
    ]);
  });
}
