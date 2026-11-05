import { preflight, serveApiDocument } from "@/lib/agent-documents";

/** Built by the API from its configuration; see lib/agent-documents.ts. */
export function GET(): Promise<Response> {
  return serveApiDocument("/.well-known/zeitwide-keys.json");
}

export function OPTIONS(): Response {
  return preflight();
}
