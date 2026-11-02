import "server-only";

import { connection } from "next/server";

/**
 * Files the pipeline writes with each dataset release (robots.txt, llms.txt, llms-full.txt and
 * the sitemaps), read from the public site-files bucket and passed through. They change only
 * when the data does, so every cache may keep them an hour.
 */

export const SITE_FILE_CACHE_CONTROL = "public, max-age=3600";
const FETCH_TIMEOUT_MS = 5_000;
const MAX_BYTES = 10 * 1024 * 1024;

const TYPES = {
  txt: "text/plain; charset=utf-8",
  xml: "application/xml; charset=utf-8",
} as const;

/** Names the pipeline writes: letters, digits, dashes and dots, in at most one folder. */
const SAFE_NAME = /^(?:[a-z]+\/)?[A-Za-z0-9][A-Za-z0-9.-]*\.(txt|xml)$/;

export async function serveSiteFile(name: string): Promise<Response> {
  // Per request, never at build: the bucket isn't known then, and a new release must show.
  await connection();
  const kind = SAFE_NAME.exec(name)?.[1] as keyof typeof TYPES | undefined;
  const base = process.env.SITE_FILES_URL;
  if (kind === undefined || base === undefined || base === "") {
    return new Response("Not found\n", { status: 404 });
  }
  const upstream = await fetch(`${base.replace(/\/$/, "")}/${name}`, {
    cache: "no-store",
    signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
  });
  if (upstream.status === 404 || upstream.status === 403) {
    return new Response("Not found\n", { status: 404 });
  }
  if (!upstream.ok) {
    return new Response("Unavailable\n", { status: 503, headers: { "Retry-After": "60" } });
  }
  const body = new Uint8Array(await upstream.arrayBuffer());
  if (body.byteLength > MAX_BYTES) {
    return new Response("Unavailable\n", { status: 503 });
  }
  return new Response(body, {
    headers: {
      "Content-Type": TYPES[kind],
      "Content-Length": String(body.byteLength),
      "Cache-Control": SITE_FILE_CACHE_CONTROL,
    },
  });
}
