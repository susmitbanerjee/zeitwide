import { docPath, findDoc } from "@/lib/docs";
import { env } from "@/lib/env";
import { toMarkdown } from "@/lib/markdown";
import { loadPage } from "@/lib/page-model-data";
import { matchPublicPath } from "@/lib/public-routes";
import { absoluteUrl } from "@/lib/seo";

const NOT_FOUND = "# Not found\n\nZeitwide has no page at this address.\n";
// As the HTML: cached at the CDN, refreshed when a release changes the calendar.
const CACHE_CONTROL = "public, max-age=0, s-maxage=300, stale-while-revalidate=86400";

/**
 * The Markdown twin of every public data page. next.config.ts rewrites /calendars/GB-CHAPS.md and
 * /calendars/GB-CHAPS?format=md here; the Cloudflare Worker maps `Accept: text/markdown` to the
 * same. The body is sent whole with its length, never chunked.
 */
export async function GET(
  _request: Request,
  { params }: RouteContext<"/api/markdown/[...path]">,
): Promise<Response> {
  const { path } = await params;
  const route = matchPublicPath(`/${path.join("/")}`);
  if (route?.type === "doc") {
    // Docs are Markdown already: served exactly as written in docs/site.
    const doc = findDoc(route.slug);
    return doc === undefined
      ? markdown(NOT_FOUND, 404)
      : markdown(doc.raw, 200, {
          Link: `<${absoluteUrl(env.siteUrl, docPath(doc.slug))}>; rel="canonical"`,
          "Cache-Control": CACHE_CONTROL,
        });
  }
  const loaded = route === null ? null : await loadPage(route);
  if (loaded === null) {
    return markdown(NOT_FOUND, 404);
  }
  if (loaded.kind === "redirect") {
    return new Response(null, {
      status: 308,
      headers: { Location: `${loaded.path}.md`, Vary: "Accept" },
    });
  }
  return markdown(toMarkdown(loaded.model), 200, {
    Link: `<${loaded.model.url}>; rel="canonical"`,
    "Cache-Control": CACHE_CONTROL,
  });
}

function markdown(body: string, status: number, headers: Record<string, string> = {}): Response {
  const bytes = new TextEncoder().encode(body);
  return new Response(bytes, {
    status,
    headers: {
      "Content-Type": "text/markdown; charset=utf-8",
      "Content-Length": String(bytes.byteLength),
      Vary: "Accept",
      ...headers,
    },
  });
}
