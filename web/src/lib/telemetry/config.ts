/** Server tracing settings, from the same OTEL_* variables the API and the pipeline read. */
export interface TracingConfig {
  /** Such as https://otlp-gateway-prod-us-central-0.grafana.net/otlp. */
  endpoint: string;
  /** From OTEL_EXPORTER_OTLP_HEADERS, such as Authorization=Basic%20abc. */
  headers: Record<string, string>;
  serviceName: string;
  sampleRatio: number;
}

const DEFAULT_SAMPLE_RATIO = 0.1;

/** Undefined when no endpoint is set: tracing is off, as locally. */
export function readTracingConfig(
  source: Record<string, string | undefined>,
): TracingConfig | undefined {
  const endpoint = source.OTEL_EXPORTER_OTLP_ENDPOINT?.trim();
  if (endpoint === undefined || endpoint === "") {
    return undefined;
  }
  return {
    endpoint: endpoint.replace(/\/+$/, ""),
    headers: parseOtlpHeaders(source.OTEL_EXPORTER_OTLP_HEADERS ?? ""),
    serviceName: source.OTEL_SERVICE_NAME ?? "zeitwide-web",
    sampleRatio: ratio(source.ZEITWIDE_TRACES_SAMPLE_RATIO),
  };
}

/** The OTLP exporter header format: comma-separated key=value pairs, values URL-encoded. */
export function parseOtlpHeaders(value: string): Record<string, string> {
  const headers: Record<string, string> = {};
  for (const pair of value.split(",")) {
    const separator = pair.indexOf("=");
    if (separator <= 0) {
      continue;
    }
    headers[pair.slice(0, separator).trim()] = decodeURIComponent(pair.slice(separator + 1).trim());
  }
  return headers;
}

function ratio(value: string | undefined): number {
  if (value === undefined || value.trim() === "") {
    return DEFAULT_SAMPLE_RATIO;
  }
  const parsed = Number(value);
  if (!Number.isFinite(parsed) || parsed < 0 || parsed > 1) {
    throw new Error("ZEITWIDE_TRACES_SAMPLE_RATIO must be a number from 0 to 1");
  }
  return parsed;
}
