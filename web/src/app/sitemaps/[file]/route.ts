import { serveSiteFile } from "@/lib/site-files";

/** One calendar's sitemap (or the pages sitemap), listed by /sitemap.xml. */
export async function GET(
  _request: Request,
  { params }: RouteContext<"/sitemaps/[file]">,
): Promise<Response> {
  return serveSiteFile(`sitemaps/${(await params).file}`);
}
