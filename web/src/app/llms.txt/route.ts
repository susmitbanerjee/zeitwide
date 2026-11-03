import { serveSiteFile } from "@/lib/site-files";

/** Written by the pipeline with each dataset release; see lib/site-files.ts. */
export function GET(): Promise<Response> {
  return serveSiteFile("llms.txt");
}
