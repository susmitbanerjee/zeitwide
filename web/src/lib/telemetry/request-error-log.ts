import { trace } from "@opentelemetry/api";

export interface RequestErrorContext {
  routePath: string;
  routeType: string;
}

/**
 * One JSON line for a failed request, in the shape Cloud Logging reads, with the trace it belongs
 * to. It names the route template, never the URL, so no query string (callback URLs, tokens)
 * reaches the logs.
 */
export function requestErrorLine(
  error: unknown,
  method: string,
  context: RequestErrorContext,
): string {
  const spanContext = trace.getActiveSpan()?.spanContext();
  const digest =
    typeof error === "object" && error !== null && "digest" in error ? error.digest : undefined;
  return JSON.stringify({
    severity: "ERROR",
    message: "request_failed",
    error: error instanceof Error ? error.name : "UnknownError",
    digest: typeof digest === "string" ? digest : undefined,
    method,
    route: context.routePath,
    route_type: context.routeType,
    trace_id: spanContext?.traceId,
    span_id: spanContext?.spanId,
  });
}
