import type { Instrumentation } from "next";

import { requestErrorLine } from "@/lib/telemetry/request-error-log";

/**
 * Server tracing to Grafana Cloud over OTLP, when OTEL_EXPORTER_OTLP_ENDPOINT is set (Cloud Run).
 * Calls to the API carry the trace context, so one trace runs web -> API -> database.
 */
export async function register(): Promise<void> {
  if (process.env.NEXT_RUNTIME === "nodejs") {
    const { registerTracing } = await import("@/lib/telemetry/register-tracing");
    registerTracing(process.env);
  }
}

export const onRequestError: Instrumentation.onRequestError = (error, request, context) => {
  console.error(requestErrorLine(error, request.method, context));
};
