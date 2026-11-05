import "server-only";

import { cacheLife } from "next/cache";
import { connection } from "next/server";

import spec from "@/api/openapi.json";
import { env } from "@/lib/env";

/**
 * The discovery documents agents look for on zeitwide.com. The API builds them from its own
 * configuration (so the MCP server card, the A2A agent card and the AI catalog can never disagree
 * with the server), and this passes them through: the web app adds no facts of its own.
 * Every one is public metadata, so any origin may read it.
 */

const FETCH_TIMEOUT_MS = 5_000;
const MAX_BYTES = 256 * 1024;
export const AGENT_DOCUMENT_CACHE_CONTROL = "public, max-age=3600";

/** The API paths passed through, and the media type each is served with. */
export const API_DOCUMENTS = {
  "/.well-known/ai-catalog.json": "application/ai-catalog+json",
  "/.well-known/agent-card.json": "application/json",
  "/.well-known/zeitwide-keys.json": "application/json",
} as const;

export type ApiDocument = keyof typeof API_DOCUMENTS;

const OPEN_CORS = {
  "Access-Control-Allow-Origin": "*",
  "Access-Control-Allow-Methods": "GET, HEAD, OPTIONS",
  "Access-Control-Allow-Headers": "Content-Type, If-None-Match",
  "Access-Control-Expose-Headers": "ETag",
} as const;

class UpstreamError extends Error {}

export async function serveApiDocument(path: ApiDocument): Promise<Response> {
  // Per request, never at build: the API isn't reachable from the image build.
  await connection();
  let body: string;
  try {
    body = await readApiDocument(path);
  } catch (error) {
    // An error thrown inside a "use cache" function reaches here re-created, not as the class it
    // was thrown as, so every failure to read the API is treated as the API being unavailable.
    console.error(
      JSON.stringify({
        severity: "WARNING",
        message: "agent_document_unavailable",
        path,
        error: error instanceof Error ? error.message : String(error),
      }),
    );
    return new Response("Unavailable\n", {
      status: 503,
      headers: { ...OPEN_CORS, "Retry-After": "60" },
    });
  }
  return json(body, API_DOCUMENTS[path]);
}

/** The OpenAPI document, as bundled with this build (a test keeps it equal to api/openapi.yaml). */
export function serveOpenApi(): Response {
  return json(JSON.stringify(spec), "application/vnd.oai.openapi+json; charset=utf-8");
}

/** The answer to a CORS preflight for any of these documents. */
export function preflight(): Response {
  return new Response(null, {
    status: 204,
    headers: { ...OPEN_CORS, "Access-Control-Max-Age": "86400" },
  });
}

function json(body: string, type: string): Response {
  const bytes = new TextEncoder().encode(body);
  return new Response(bytes, {
    headers: {
      ...OPEN_CORS,
      "Content-Type": type,
      "Content-Length": String(bytes.byteLength),
      "Cache-Control": AGENT_DOCUMENT_CACHE_CONTROL,
      "X-Content-Type-Options": "nosniff",
    },
  });
}

/** Kept an hour, like the API's own Cache-Control; a failure throws, so it is never kept. */
async function readApiDocument(path: ApiDocument): Promise<string> {
  "use cache";
  cacheLife("siteFiles");
  let upstream: Response;
  try {
    upstream = await fetch(`${env.apiUrl}${path}`, {
      cache: "no-store",
      headers: { Accept: "application/json" },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
  } catch (cause) {
    throw new UpstreamError(`The API did not answer for ${path}`, { cause });
  }
  if (!upstream.ok) {
    throw new UpstreamError(`The API answered ${upstream.status} for ${path}`);
  }
  const text = await upstream.text();
  if (new TextEncoder().encode(text).byteLength > MAX_BYTES) {
    throw new UpstreamError(`The API's ${path} is larger than ${MAX_BYTES} bytes`);
  }
  // Only well-formed JSON is passed on; anything else is an upstream fault.
  try {
    JSON.parse(text);
  } catch (cause) {
    throw new UpstreamError(`The API's ${path} is not JSON`, { cause });
  }
  return text;
}
