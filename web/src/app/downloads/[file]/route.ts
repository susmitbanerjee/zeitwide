import { serveSiteFile } from "@/lib/site-files";

/** A calendar's entries as CSV, JSON or iCalendar, written by the pipeline with each release. */
export async function GET(
  _request: Request,
  { params }: RouteContext<"/downloads/[file]">,
): Promise<Response> {
  return serveSiteFile(`downloads/${(await params).file}`);
}
