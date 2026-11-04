import "server-only";

import { cacheLife, cacheTag } from "next/cache";
import { connection } from "next/server";

/**
 * Files the pipeline writes with each dataset release (robots.txt, llms.txt, llms-full.txt, the
 * sitemaps and each calendar's CSV, JSON and iCalendar downloads), read from the private site-files bucket and passed through. Each instance keeps
 * a file for an hour, so most requests never reach the bucket; nobody can read it but us.
 */

export const SITE_FILE_CACHE_CONTROL = "public, max-age=3600";
const SITE_FILES_TAG = "site-files";
const FETCH_TIMEOUT_MS = 5_000;
const MAX_BYTES = 10 * 1024 * 1024;
// The token server on Cloud Run: the service account's OAuth token, never a stored key.
const METADATA_TOKEN_URL =
  "http://metadata.google.internal/computeMetadata/v1/instance/service-accounts/default/token";
const TOKEN_MARGIN_MS = 60_000;

const TYPES = {
  txt: "text/plain; charset=utf-8",
  xml: "application/xml; charset=utf-8",
  csv: "text/csv; charset=utf-8",
  json: "application/json; charset=utf-8",
  ics: "text/calendar; charset=utf-8",
} as const;

/** Names the pipeline writes: letters, digits, dashes and dots, in at most one folder. */
const SAFE_NAME = /^(?:[a-z]+\/)?[A-Za-z0-9][A-Za-z0-9.-]*\.(txt|xml|csv|json|ics)$/;

class UpstreamError extends Error {}

export async function serveSiteFile(name: string): Promise<Response> {
  // Per request, never at build: the bucket isn't known then, and a new release must show.
  await connection();
  const kind = SAFE_NAME.exec(name)?.[1] as keyof typeof TYPES | undefined;
  const base = process.env.SITE_FILES_URL;
  if (kind === undefined || base === undefined || base === "") {
    return new Response("Not found\n", { status: 404 });
  }
  let body: string | null;
  try {
    body = await readSiteFile(`${base.replace(/\/$/, "")}/${name}`);
  } catch (error) {
    if (error instanceof UpstreamError) {
      return new Response("Unavailable\n", { status: 503, headers: { "Retry-After": "60" } });
    }
    throw error;
  }
  if (body === null) {
    return new Response("Not found\n", { status: 404 });
  }
  const bytes = new TextEncoder().encode(body);
  return new Response(bytes, {
    headers: {
      "Content-Type": TYPES[kind],
      "Content-Length": String(bytes.byteLength),
      "Cache-Control": SITE_FILE_CACHE_CONTROL,
    },
  });
}

/**
 * The file's text, or null when the bucket has no such file. Both are kept an hour; a failure
 * throws, so it is never kept.
 */
async function readSiteFile(url: string): Promise<string | null> {
  "use cache";
  cacheLife("siteFiles");
  cacheTag(SITE_FILES_TAG);
  const upstream = await fetch(url, {
    cache: "no-store",
    headers: await authorization(),
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
  if (upstream.status === 404) {
    return null;
  }
  if (!upstream.ok) {
    throw new UpstreamError(`Site file ${url} answered ${upstream.status}`);
  }
  const body = await upstream.arrayBuffer();
  if (body.byteLength > MAX_BYTES) {
    throw new UpstreamError(`Site file ${url} is over ${MAX_BYTES} bytes`);
  }
  return new TextDecoder().decode(body);
}

let token: { value: string; expiresAt: number } | null = null;

/** The Authorization header for the bucket: none locally, the service account's token on GCP. */
async function authorization(): Promise<Record<string, string>> {
  if (process.env.SITE_FILES_AUTH !== "gcp-metadata") {
    return {};
  }
  if (token === null || token.expiresAt - TOKEN_MARGIN_MS < Date.now()) {
    const response = await fetch(METADATA_TOKEN_URL, {
      cache: "no-store",
      headers: { "Metadata-Flavor": "Google" },
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
    if (!response.ok) {
      throw new UpstreamError(`Metadata token server answered ${response.status}`);
    }
    const { access_token: value, expires_in: seconds } = (await response.json()) as {
      access_token: string;
      expires_in: number;
    };
    token = { value, expiresAt: Date.now() + seconds * 1000 };
  }
  return { Authorization: `Bearer ${token.value}` };
}
