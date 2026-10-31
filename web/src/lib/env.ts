import "server-only";

/**
 * Server configuration. Secrets (the API key, AUTH_SECRET) never leave the server: nothing here
 * is prefixed NEXT_PUBLIC_, so none of it reaches the browser bundle.
 */
export interface ServerEnv {
  /** The Zeitwide API, such as https://api.zeitwide.com. */
  apiUrl: string;
  /** Raises the anonymous rate limit for prerendering; optional locally. */
  apiKey: string | undefined;
  /** The public origin: answer pages, docs and canonical URLs. Served through Firebase Hosting. */
  siteUrl: string;
  /** The signed-in origin: app, admin and sign-in. Served straight from Cloud Run. */
  appUrl: string;
  /** The hosted MCP endpoint shown on the MCP setup page. */
  mcpUrl: string;
}

export function readServerEnv(source: Record<string, string | undefined> = process.env): ServerEnv {
  const apiUrl = origin(source.ZEITWIDE_API_URL ?? "http://localhost:8080", "ZEITWIDE_API_URL");
  const siteUrl = origin(source.SITE_URL ?? "http://localhost:3000", "SITE_URL");
  return {
    apiUrl,
    apiKey: nonEmpty(source.ZEITWIDE_API_KEY),
    siteUrl,
    appUrl: origin(source.APP_URL ?? siteUrl, "APP_URL"),
    mcpUrl: source.ZEITWIDE_MCP_URL ?? `${apiUrl}/mcp`,
  };
}

export const env: ServerEnv = readServerEnv();

function origin(value: string, name: string): string {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new Error(`${name} must be an absolute URL`);
  }
  if (url.protocol !== "https:" && url.protocol !== "http:") {
    throw new Error(`${name} must use http or https`);
  }
  return url.origin;
}

function nonEmpty(value: string | undefined): string | undefined {
  return value === undefined || value.trim() === "" ? undefined : value.trim();
}
