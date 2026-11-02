import { serveSiteFile } from "@/lib/site-files";

/** Every doc in one file, written by the pipeline with each dataset release. */
export function GET(): Promise<Response> {
  return serveSiteFile("docs/llms-full.txt");
}
