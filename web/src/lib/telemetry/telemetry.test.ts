import { ROOT_CONTEXT, SpanStatusCode, trace, TraceFlags, type Tracer } from "@opentelemetry/api";
import {
  BasicTracerProvider,
  InMemorySpanExporter,
  SimpleSpanProcessor,
} from "@opentelemetry/sdk-trace-base";
import { describe, expect, it } from "vitest";

import { parseOtlpHeaders, readTracingConfig } from "./config";
import { faroOrigin, withoutQuery } from "./faro-options";
import { requestErrorLine } from "./request-error-log";
import { ErrorKeepingSpanProcessor, isTraceSampled } from "./trace-sampling";

const KEPT = "0123456789abcdef0000000000000001";
const DROPPED = "0123456789abcdefffffffffffffffff";

describe("isTraceSampled", () => {
  // The same IDs and answers as TraceIdRatioTest.java and pipeline/tests/test_telemetry.py.
  it("decides from the lower half of the trace ID", () => {
    expect(isTraceSampled("ffffffffffffffff0000000000000000", 0.1)).toBe(true);
    expect(isTraceSampled("0000000000000000199999999999a000", 0.1)).toBe(false);
    expect(isTraceSampled("00000000000000001999999999999800", 0.1)).toBe(true);
    expect(isTraceSampled("0000000000000000ffffffffffffffff", 0.1)).toBe(false);
  });

  it("keeps everything at 1 and nothing at 0", () => {
    expect(isTraceSampled("0000000000000000ffffffffffffffff", 1)).toBe(true);
    expect(isTraceSampled("ffffffffffffffff0000000000000000", 0)).toBe(false);
  });

  it("rejects an ID that is not 32 hex characters", () => {
    expect(() => isTraceSampled("abc", 0.1)).toThrow();
  });
});

describe("ErrorKeepingSpanProcessor", () => {
  function setup(ratio: number, maxPending = 16) {
    const exported = new InMemorySpanExporter();
    const processor = new ErrorKeepingSpanProcessor(
      new SimpleSpanProcessor(exported),
      ratio,
      maxPending,
    );
    const tracer = new BasicTracerProvider({ spanProcessors: [processor] }).getTracer("test");
    const names = () => exported.getFinishedSpans().map((span) => span.name);
    return { tracer, processor, names };
  }

  function remoteParent(traceId: string) {
    return trace.setSpanContext(ROOT_CONTEXT, {
      traceId,
      spanId: "00f067aa0ba902b7",
      traceFlags: TraceFlags.SAMPLED,
      isRemote: true,
    });
  }

  function rootWithChild(tracer: Tracer, traceId: string, childFails: boolean) {
    const root = tracer.startSpan("root", {}, remoteParent(traceId));
    const child = tracer.startSpan("child", {}, trace.setSpan(ROOT_CONTEXT, root));
    if (childFails) {
      child.setStatus({ code: SpanStatusCode.ERROR });
    }
    child.end();
    root.end();
  }

  it("keeps a whole trace in the sampled fraction", () => {
    const { tracer, names } = setup(0.1);
    rootWithChild(tracer, KEPT, false);
    expect(names()).toEqual(["child", "root"]);
  });

  it("drops a trace outside the fraction", () => {
    const { tracer, names } = setup(0.1);
    rootWithChild(tracer, DROPPED, false);
    expect(names()).toEqual([]);
  });

  it("keeps a trace outside the fraction when a span failed", () => {
    const { tracer, names } = setup(0.1);
    rootWithChild(tracer, DROPPED, true);
    expect(names()).toEqual(["child", "root"]);
  });

  it("drops health checks", () => {
    const { tracer, names } = setup(1);
    tracer
      .startSpan(
        "GET /api/health",
        { attributes: { "next.route": "/api/health" } },
        remoteParent(KEPT),
      )
      .end();
    expect(names()).toEqual([]);
  });

  it("exports a span that ends after its kept root", () => {
    const { tracer, names } = setup(0.1);
    const root = tracer.startSpan("root", {}, remoteParent(KEPT));
    const late = tracer.startSpan("late", {}, trace.setSpan(ROOT_CONTEXT, root));
    root.end();
    late.end();
    expect(names()).toEqual(["root", "late"]);
  });

  it("decides the oldest pending trace once too many are pending", () => {
    const { tracer, names } = setup(1, 1);
    const first = tracer.startSpan("first-root", {}, remoteParent(KEPT));
    const second = tracer.startSpan("second-root", {}, remoteParent(DROPPED));
    tracer.startSpan("first-child", {}, trace.setSpan(ROOT_CONTEXT, first)).end();
    tracer.startSpan("second-child", {}, trace.setSpan(ROOT_CONTEXT, second)).end();
    expect(names()).toEqual(["first-child"]);
  });

  it("releases pending sampled spans on flush", async () => {
    const { tracer, processor, names } = setup(1);
    const root = tracer.startSpan("root", {}, remoteParent(KEPT));
    tracer.startSpan("child", {}, trace.setSpan(ROOT_CONTEXT, root)).end();
    await processor.forceFlush();
    expect(names()).toEqual(["child"]);
  });

  it("rejects a ratio outside 0 to 1", () => {
    expect(
      () => new ErrorKeepingSpanProcessor(new SimpleSpanProcessor(new InMemorySpanExporter()), 2),
    ).toThrow();
  });
});

describe("readTracingConfig", () => {
  it("is off without an endpoint", () => {
    expect(readTracingConfig({})).toBeUndefined();
    expect(readTracingConfig({ OTEL_EXPORTER_OTLP_ENDPOINT: " " })).toBeUndefined();
  });

  it("reads the OTEL variables the API and pipeline use", () => {
    expect(
      readTracingConfig({
        OTEL_EXPORTER_OTLP_ENDPOINT: "https://otlp.example.test/otlp/",
        OTEL_EXPORTER_OTLP_HEADERS: "Authorization=Basic%20abc%3D",
        OTEL_SERVICE_NAME: "zeitwide-web-dev",
        ZEITWIDE_TRACES_SAMPLE_RATIO: "0.5",
      }),
    ).toEqual({
      endpoint: "https://otlp.example.test/otlp",
      headers: { Authorization: "Basic abc=" },
      serviceName: "zeitwide-web-dev",
      sampleRatio: 0.5,
    });
  });

  it("samples a tenth by default and rejects a bad ratio", () => {
    const base = { OTEL_EXPORTER_OTLP_ENDPOINT: "https://otlp.example.test" };
    expect(readTracingConfig(base)?.sampleRatio).toBe(0.1);
    expect(() => readTracingConfig({ ...base, ZEITWIDE_TRACES_SAMPLE_RATIO: "2" })).toThrow();
  });

  it("skips malformed header pairs", () => {
    expect(parseOtlpHeaders("a=1,broken,=x, b = 2 ")).toEqual({ a: "1", b: "2" });
  });
});

describe("faro options", () => {
  it("allows only an https collector", () => {
    expect(faroOrigin(undefined)).toBeUndefined();
    expect(faroOrigin("")).toBeUndefined();
    expect(faroOrigin("https://faro.example.test/collect/abc")).toBe("https://faro.example.test");
    expect(() => faroOrigin("http://faro.example.test/collect")).toThrow();
  });

  it("drops query strings and fragments from page URLs", () => {
    expect(withoutQuery("https://app.example.test/signin?callbackUrl=%2Fapp#x")).toBe(
      "https://app.example.test/signin",
    );
    expect(withoutQuery("https://example.test/calendars")).toBe("https://example.test/calendars");
  });
});

describe("requestErrorLine", () => {
  it("logs the route template and error type, never the message", () => {
    const error = Object.assign(new TypeError("secret detail"), { digest: "123" });
    const line = JSON.parse(
      requestErrorLine(error, "GET", { routePath: "/app/keys", routeType: "render" }),
    ) as Record<string, unknown>;
    expect(line).toMatchObject({
      severity: "ERROR",
      error: "TypeError",
      digest: "123",
      method: "GET",
      route: "/app/keys",
    });
    expect(JSON.stringify(line)).not.toContain("secret detail");
  });

  it("names an unknown thrown value", () => {
    const line = JSON.parse(
      requestErrorLine("boom", "POST", { routePath: "/api/x", routeType: "route" }),
    ) as Record<string, unknown>;
    expect(line.error).toBe("UnknownError");
  });
});
