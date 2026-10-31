import createClient, { type Client } from "openapi-fetch";

import type { components, paths } from "./schema";

/** The Zeitwide API, typed from api/openapi.yaml. Every date on screen comes from here. */
export type ZeitwideClient = Client<paths>;

type Schemas = components["schemas"];
export type AlertRule = Schemas["AlertRule"];
export type AlertRuleConfiguration = Schemas["AlertRuleConfiguration"];
export type ApiKeySummary = Schemas["ApiKeySummary"];
export type Calendar = Schemas["Calendar"];
export type CalendarDay = Schemas["CalendarDay"];
export type CalendarList = Schemas["CalendarList"];
export type Candidate = Schemas["Candidate"];
export type CandidateDetail = Schemas["CandidateDetail"];
export type CandidateSnapshot = Schemas["CandidateSnapshot"];
export type CandidateStatus = Schemas["CandidateStatus"];
export type Change = Schemas["Change"];
export type ChangeType = Schemas["ChangeType"];
export type CreatedApiKey = Schemas["CreatedApiKey"];
export type CreatedWebhook = Schemas["CreatedWebhook"];
export type DayAnswer = Schemas["DayAnswer"];
export type DayRange = Schemas["DayRange"];
export type EntryState = Schemas["EntryState"];
export type Problem = Schemas["Problem"];
export type RangeDay = Schemas["RangeDay"];
export type Receipt = Schemas["Receipt"];
export type Release = Schemas["Release"];
export type Shifted = Schemas["Shifted"];
export type Source = Schemas["Source"];
export type SourceHealth = Schemas["SourceHealth"];
export type WebhookSubscription = Schemas["WebhookSubscription"];

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

/** Raised when the API answers with RFC 9457 problem details. */
export class ApiProblem extends Error {
  readonly status: number;
  readonly code: Problem["code"] | "network_error";

  constructor(status: number, code: Problem["code"] | "network_error", detail: string) {
    super(detail);
    this.name = "ApiProblem";
    this.status = status;
    this.code = code;
  }

  static from(response: Response, problem: unknown): ApiProblem {
    if (isProblem(problem)) {
      return new ApiProblem(response.status, problem.code, problem.detail ?? problem.title);
    }
    return new ApiProblem(response.status, "internal_error", `HTTP ${response.status}`);
  }
}

function isProblem(value: unknown): value is Problem {
  return (
    typeof value === "object" &&
    value !== null &&
    "code" in value &&
    typeof value.code === "string" &&
    "title" in value &&
    typeof value.title === "string"
  );
}

/** Unwraps an openapi-fetch result: the data, or an {@link ApiProblem}. */
export function unwrap<T>(result: { data?: T; error?: unknown; response: Response }): T {
  if (result.data !== undefined) {
    return result.data;
  }
  throw ApiProblem.from(result.response, result.error);
}
