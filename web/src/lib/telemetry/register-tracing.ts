import { BatchSpanProcessor } from "@opentelemetry/sdk-trace-base";
import { OTLPHttpProtoTraceExporter, registerOTel } from "@vercel/otel";

import { readTracingConfig } from "./config";
import { ErrorKeepingSpanProcessor } from "./trace-sampling";

export function registerTracing(source: Record<string, string | undefined>): void {
  const config = readTracingConfig(source);
  if (config === undefined) {
    return;
  }
  const apiOrigin = new URL(source.ZEITWIDE_API_URL ?? "http://localhost:8080").origin;
  const exporter = new OTLPHttpProtoTraceExporter({
    url: `${config.endpoint}/v1/traces`,
    headers: config.headers,
  });
  registerOTel({
    serviceName: config.serviceName,
    // Every span is recorded; ErrorKeepingSpanProcessor picks which traces leave the process.
    traceSampler: "always_on",
    spanProcessors: [
      new ErrorKeepingSpanProcessor(new BatchSpanProcessor(exporter), config.sampleRatio),
    ],
    instrumentationConfig: {
      // Only the API gets the trace context; storage and identity providers don't need it.
      fetch: { propagateContextUrls: [apiOrigin] },
    },
  });
}
