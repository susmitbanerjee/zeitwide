import { preflight, serveOpenApi } from "@/lib/agent-documents";

/** The REST API's OpenAPI 3.1 document, linked from the AI catalog and llms.txt. */
export function GET(): Response {
  return serveOpenApi();
}

export function OPTIONS(): Response {
  return preflight();
}
