import createClient, { type Client } from "openapi-fetch";

import type { components, paths } from "./schema";

/** The Zeitwide API, typed from api/openapi.yaml. Every date on screen comes from here. */
export type ZeitwideClient = Client<paths>;

export type DayAnswer = components["schemas"]["DayAnswer"];
export type Shifted = components["schemas"]["Shifted"];
export type Problem = components["schemas"]["Problem"];

export interface ClientOptions {
  baseUrl: string;
  /**
   * Server-side only. An API key in browser code would ship to every visitor; anonymous calls
   * work without one.
   */
  apiKey?: string;
  fetch?: typeof globalThis.fetch;
}

export function createZeitwideClient(options: ClientOptions): ZeitwideClient {
  const headers: Record<string, string> = {};
  if (options.apiKey !== undefined) {
    headers["Authorization"] = `Bearer ${options.apiKey}`;
  }
  return createClient<paths>({
    baseUrl: options.baseUrl,
    headers,
    ...(options.fetch === undefined ? {} : { fetch: options.fetch }),
  });
}
