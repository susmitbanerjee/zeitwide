import type { NextConfig } from "next";

import { SECURITY_HEADERS } from "./src/lib/security-headers";

const nextConfig: NextConfig = {
  // A minimal server.js plus only the files it needs, for the container image.
  output: "standalone",
  cacheComponents: true,
  partialPrefetching: true,
  poweredByHeader: false,
  cacheLife: {
    // Answer data: a calendar changes only when a release is cut, and the cache key holds its
    // revision, so a new revision is fetched as soon as the calendar list shows it.
    calendar: { stale: 300, revalidate: 300, expire: 86_400 },
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
    return Promise.resolve([{ source: "/:path*", headers: SECURITY_HEADERS }]);
  },
};

export default nextConfig;
