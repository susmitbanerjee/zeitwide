import { SpanStatusCode, type Context } from "@opentelemetry/api";
import type { ReadableSpan, Span, SpanProcessor } from "@opentelemetry/sdk-trace-base";

const TWO_TO_THE_53 = 2 ** 53;
const HEALTH_PATH = "/api/health";
// Attributes Next.js and the fetch instrumentation put the request path in.
const PATH_ATTRIBUTES = ["next.route", "http.route", "http.target", "url.path"];

/**
 * Whether a trace is in the sampled fraction, from its ID alone. The API and the pipeline use the
 * same rule (TraceIdRatio.java, telemetry.py), so all three keep the same traces whole: the top
 * 53 bits of the ID's random lower half, read as a fraction in [0, 1).
 */
export function isTraceSampled(traceId: string, ratio: number): boolean {
  if (!/^[0-9a-f]{32}$/.test(traceId)) {
    throw new Error("A trace ID is 32 lowercase hex characters");
  }
  const lowerHalf = BigInt(`0x${traceId.slice(16)}`);
  return Number(lowerHalf >> 11n) / TWO_TO_THE_53 < ratio;
}

/**
 * Keeps a fixed fraction of traces plus every trace with an error, and drops health checks.
 * Record every span (sampler always_on): this holds a trace's spans until its local root ends,
 * then passes them on to the exporting processor only if the trace is kept.
 */
export class ErrorKeepingSpanProcessor implements SpanProcessor {
  private readonly pending = new Map<string, ReadableSpan[]>();
  private readonly decided = new Map<string, boolean>();

  constructor(
    private readonly exporter: SpanProcessor,
    private readonly ratio: number,
    private readonly maxPendingTraces = 2048,
  ) {
    if (ratio < 0 || ratio > 1) {
      throw new Error("The sample ratio is between 0 and 1");
    }
  }

  onStart(span: Span, parentContext: Context): void {
    this.exporter.onStart(span, parentContext);
  }

  onEnd(span: ReadableSpan): void {
    const traceId = span.spanContext().traceId;
    const keep = this.decided.get(traceId);
    if (keep !== undefined) {
      if (keep) {
        this.exporter.onEnd(span);
      }
      return;
    }
    const spans = this.pending.get(traceId) ?? [];
    spans.push(span);
    this.pending.set(traceId, spans);
    if (isLocalRoot(span)) {
      this.release(traceId, !isHealthCheck(span));
    } else if (this.pending.size > this.maxPendingTraces) {
      const oldest = this.pending.keys().next().value;
      if (oldest !== undefined) {
        this.release(oldest, true);
      }
    }
  }

  async forceFlush(): Promise<void> {
    this.releaseAll();
    await this.exporter.forceFlush();
  }

  async shutdown(): Promise<void> {
    this.releaseAll();
    await this.exporter.shutdown();
  }

  private releaseAll(): void {
    for (const traceId of [...this.pending.keys()]) {
      this.release(traceId, true);
    }
  }

  private release(traceId: string, sampleable: boolean): void {
    const spans = this.pending.get(traceId) ?? [];
    this.pending.delete(traceId);
    const failed = spans.some((span) => span.status.code === SpanStatusCode.ERROR);
    const keep = failed || (sampleable && isTraceSampled(traceId, this.ratio));
    this.remember(traceId, keep);
    if (keep) {
      spans.forEach((span) => this.exporter.onEnd(span));
    }
  }

  private remember(traceId: string, keep: boolean): void {
    this.decided.set(traceId, keep);
    if (this.decided.size > this.maxPendingTraces) {
      const oldest = this.decided.keys().next().value;
      if (oldest !== undefined) {
        this.decided.delete(oldest);
      }
    }
  }
}

function isLocalRoot(span: ReadableSpan): boolean {
  const parent = span.parentSpanContext;
  return parent === undefined || parent.isRemote === true;
}

function isHealthCheck(span: ReadableSpan): boolean {
  return PATH_ATTRIBUTES.some((name) => {
    const value = span.attributes[name];
    return typeof value === "string" && value.startsWith(HEALTH_PATH);
  });
}
