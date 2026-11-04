/**
 * Path templates: the telemetry label for a path (never the path itself), and whether the page has
 * a Markdown twin. The public templates mirror matchPublicPath in web/src/lib/public-routes.ts;
 * test/paths.test.ts holds the same cases as web's test. When a page type is added there, add it
 * here too. Getting it wrong is safe: a path left out is passed through as HTML.
 */
export interface PathInfo {
  readonly template: string;
  /** A public page with a `.md` twin at the origin. */
  readonly negotiable: boolean;
}

const YEAR = /^\d{4}$/;
const ISO_DATE = /^\d{4}-(0[1-9]|1[0-2])-(0[1-9]|[12]\d|3[01])$/;

/** Fixed files and prefixes, matched before the public pages. Each label is its own template. */
const FIXED: readonly (readonly [prefix: string, template: string])[] = [
  ["/_next/", "/_next/*"],
  ["/api/", "/api/*"],
  ["/app/", "/app/*"],
  ["/admin/", "/admin/*"],
  ["/sitemaps/", "/sitemaps/*"],
  ["/downloads/", "/downloads/*"],
  ["/.well-known/", "/.well-known/*"],
];
const FILES = new Set([
  "/robots.txt",
  "/llms.txt",
  "/llms-full.txt",
  "/docs/llms-full.txt",
  "/sitemap.xml",
  "/favicon.ico",
  "/icon.svg",
  "/logo.png",
  "/signin",
  "/signup",
  "/app",
  "/admin",
]);

export function pathInfo(pathname: string): PathInfo {
  if (pathname === "/") {
    return { template: "/", negotiable: false };
  }
  if (FILES.has(pathname)) {
    return { template: pathname, negotiable: false };
  }
  const fixed = FIXED.find(([prefix]) => pathname.startsWith(prefix));
  if (fixed !== undefined) {
    return { template: fixed[1], negotiable: false };
  }
  const twin = pathname.endsWith(".md");
  const page = publicTemplate(twin ? pathname.slice(0, -3) : pathname);
  if (page === null) {
    return { template: "other", negotiable: false };
  }
  return twin
    ? { template: `${page}.md`, negotiable: false }
    : { template: page, negotiable: true };
}

function publicTemplate(pathname: string): string | null {
  const parts = pathname.split("/").filter((part) => part !== "");
  const [first, second, third, ...rest] = parts;
  if (rest.length > 0 || parts.some((part) => !isDecodable(part))) {
    return null;
  }
  if (first === "calendars") {
    if (second === undefined) {
      return "/calendars";
    }
    if (third === undefined) {
      return "/calendars/:id";
    }
    if (YEAR.test(third)) {
      return "/calendars/:id/:year";
    }
    return ISO_DATE.test(third) ? "/calendars/:id/:date" : null;
  }
  if (first === "compare" && second !== undefined && third !== undefined && YEAR.test(third)) {
    return "/compare/:pair/:year";
  }
  if (first === "docs" && third === undefined) {
    return second === undefined ? "/docs" : "/docs/:slug";
  }
  if (second === undefined && (first === "sources" || first === "pricing")) {
    return `/${first}`;
  }
  return null;
}

function isDecodable(segment: string): boolean {
  try {
    decodeURIComponent(segment);
    return true;
  } catch {
    return false;
  }
}
