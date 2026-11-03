import type { NextConfig } from "next";

import { SECURITY_HEADERS } from "./src/lib/security-headers";

const nextConfig: NextConfig = {
  // A minimal server.js plus only the files it needs, for the container image.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  experimental: {
    // Prerendering calls the API once per page, and the API's Postgres pool has 4 connections.
    // Seven workers exhausted that pool, so the build runs two.
    cpus: 2,
    // Each page asks the API about every calendar, and the API's database is far away, so a
    // call takes seconds. Eight pages at once per worker put dozens of calls in flight at the
    // same moment and the API timed out; fewer at a time, and a few more tries, keep the build
    // from failing on a slow moment.
    staticGenerationMaxConcurrency: 3,
    staticGenerationRetryCount: 3,
  },
  poweredByHeader: false,
  cacheLife: {
    // Answer data: a calendar changes only when a release is cut, and the cache key holds its
    // revision, so a new revision is fetched as soon as the calendar list shows it.
    calendar: { stale: 300, revalidate: 300, expire: 86_400 },
    // robots.txt, llms.txt and the sitemaps: they change once a release, at most daily.
    siteFiles: { stale: 300, revalidate: 3600, expire: 86_400 },
  },
  turbopack: {
    rules: {
      "*.css": {
        loaders: ["@tailwindcss/turbopack"],
        as: "*.css",
      },
    },
  },
  // The Content-Security-Policy is set per request in src/proxy.ts (nonces for signed-in pages).
  headers() {
    return Promise.resolve([
      { source: "/:path*", headers: SECURITY_HEADERS },
      // Public pages have a Markdown twin; the Cloudflare Worker picks one by Accept.
      {
        source: "/:path((?!api/|_next/|app/|admin/).*)",
        headers: [{ key: "Vary", value: "Accept" }],
      },
    ]);
  },
  // Markdown for every public data page: /calendars/GB-CHAPS.md or /calendars/GB-CHAPS?format=md.
  rewrites() {
    return Promise.resolve({
      beforeFiles: [
        { source: "/:path((?!api/).+)\\.md", destination: "/api/markdown/:path" },
        {
          source: "/:path((?!api/).+)",
          has: [{ type: "query", key: "format", value: "md" }],
          destination: "/api/markdown/:path",
        },
      ],
      afterFiles: [],
      fallback: [],
    });
  },
};

export default nextConfig;
