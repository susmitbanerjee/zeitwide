import { beforeEach, describe, expect, it, vi } from "vitest";

const registerOTel = vi.fn();
vi.mock("@vercel/otel", () => ({
  registerOTel,
  OTLPHttpProtoTraceExporter: class {
    constructor(readonly config: unknown) {}
  },
}));

const { registerTracing } = await import("./register-tracing");

describe("registerTracing", () => {
  beforeEach(() => registerOTel.mockClear());

  it("does nothing without an OTLP endpoint", () => {
    registerTracing({});
    expect(registerOTel).not.toHaveBeenCalled();
  });

  it("records every span and propagates trace context only to the API", () => {
    registerTracing({
      OTEL_EXPORTER_OTLP_ENDPOINT: "https://otlp.example.test/otlp",
      ZEITWIDE_API_URL: "https://api.example.test/v1",
    });
    expect(registerOTel).toHaveBeenCalledWith(
      expect.objectContaining({
        serviceName: "zeitwide-web",
        traceSampler: "always_on",
        instrumentationConfig: { fetch: { propagateContextUrls: ["https://api.example.test"] } },
      }),
    );
  });
});
