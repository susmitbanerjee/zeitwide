import { fileURLToPath } from "node:url";

import { Headers, Miniflare, Response, type Request, type RequestInit } from "miniflare";

export const SITE = "https://zeitwide.example";

/** What the fake origin and vendors saw, and the data points the Worker wrote. */
export interface Recorded {
  readonly origin: string[];
  readonly points: { blobs: string[]; doubles: number[]; indexes: string[] }[];
  readonly loki: unknown[];
  readonly sql: string[];
}

export const HTML = "<!doctype html><title>GB-CHAPS</title><p>Open</p>";
export const MARKDOWN = "# GB-CHAPS\n\nOpen.\n";

export const VENDOR_RANGES: Record<string, object> = {
  "https://openai.com/gptbot.json": {
    creationTime: "2026-11-01T00:00:00",
    prefixes: [{ ipv4Prefix: "20.171.206.0/24" }],
  },
  "https://claude.com/crawling/bots.json": {
    prefixes: [{ ipv4Prefix: "216.73.216.0/22" }, { ipv6Prefix: "2600:1f28:365:80b0::/60" }],
  },
};

async function origin(request: Request, recorded: Recorded): Promise<Response> {
  const url = new URL(request.url);
  if (url.origin === SITE) {
    recorded.origin.push(`${url.pathname}${url.search}`);
    if (url.pathname.endsWith(".md")) {
      return new Response(MARKDOWN, {
        headers: { "content-type": "text/markdown; charset=utf-8", vary: "Accept" },
      });
    }
    if (url.pathname.startsWith("/_next/")) {
      return new Response("console.log(1)", {
        headers: { "content-type": "application/javascript" },
      });
    }
    return new Response(HTML, {
      headers: {
        "content-type": "text/html; charset=utf-8",
        "cf-cache-status": "DYNAMIC",
        vary: "rsc",
      },
    });
  }
  const ranges = VENDOR_RANGES[request.url];
  if (ranges !== undefined) {
    return Response.json(ranges);
  }
  if (url.hostname === "api.cloudflare.com") {
    recorded.sql.push(await request.text());
    return Response.json({
      data: [
        {
          template: "/calendars/:id",
          status: "200",
          family: "training",
          vendor: "openai",
          verified: "1",
          markdown: "0",
          requests: "12",
          bytes: "3400",
        },
        {
          template: "/calendars/:id",
          status: "200",
          family: "human",
          vendor: "",
          verified: "0",
          markdown: "0",
          requests: "30",
          bytes: "9000",
        },
      ],
      rows: 2,
    });
  }
  if (url.hostname === "loki.example") {
    recorded.loki.push(await request.json());
    return new Response(null, { status: 204 });
  }
  return new Response("not found", { status: 404 });
}

/** The built Worker (dist/index.js) in Miniflare, with a fake origin, vendors, SQL API and Loki. */
export async function startWorker(options: { botLimit?: number } = {}) {
  const recorded: Recorded = { origin: [], points: [], loki: [], sql: [] };
  const mf = new Miniflare({
    workers: [
      {
        name: "edge",
        modules: true,
        scriptPath: fileURLToPath(new URL("../dist/index.js", import.meta.url)),
        compatibilityDate: "2026-08-01",
        kvNamespaces: ["RANGES"],
        ratelimits: {
          BOT_LIMIT: {
            namespace_id: "1001",
            simple: { limit: options.botLimit ?? 60, period: 60 },
          },
        },
        bindings: {
          ENVIRONMENT: "test",
          DEBUG_LABELS: "1",
          CF_ACCOUNT_ID: "account-1",
          CF_ANALYTICS_TOKEN: "test-analytics-token",
          LOKI_PUSH_URL: "https://loki.example/loki/api/v1/push",
          LOKI_USER: "123",
          LOKI_TOKEN: "test-loki-token",
        },
        wrappedBindings: { EDGE_EVENTS: { scriptName: "analytics-engine-fake" } },
        outboundService: (request: Request) => origin(request, recorded),
      },
      {
        // Stands in for Analytics Engine: each data point is sent to the test process.
        name: "analytics-engine-fake",
        modules: true,
        script: `export default function (env) {
          return { writeDataPoint(point) { env.SINK.fetch("http://sink/", { method: "POST", body: JSON.stringify(point) }); } };
        }`,
        serviceBindings: {
          SINK: async (request: Request) => {
            recorded.points.push((await request.json()) as Recorded["points"][number]);
            return new Response(null, { status: 204 });
          },
        },
      },
    ],
  });
  await mf.ready;

  return {
    mf,
    recorded,
    async fetch(path: string, init: RequestInit & { ip?: string; country?: string } = {}) {
      const headers = new Headers(init.headers);
      headers.set("cf-connecting-ip", init.ip ?? "198.51.100.7");
      const response = await mf.dispatchFetch(`${SITE}${path}`, {
        ...init,
        headers,
        cf: { country: (init.country ?? "GB") as "GB" },
      });
      const body = await response.text();
      return { response, body, labels: response.headers.get("x-zw-edge") };
    },
    /** Waits until the Worker has written `count` data points (they arrive after the response). */
    async points(count: number) {
      for (let i = 0; i < 100 && recorded.points.length < count; i++) {
        await new Promise((resolve) => setTimeout(resolve, 20));
      }
      return recorded.points;
    },
    async seedRanges() {
      const kv = await mf.getKVNamespace("RANGES");
      const worker = await mf.getWorker();
      await worker.scheduled({
        cron: "17 3 * * *",
        scheduledTime: new Date("2026-11-04T03:17:00Z"),
      });
      return kv;
    },
    dispose: () => mf.dispose(),
  };
}

export type Edge = Awaited<ReturnType<typeof startWorker>>;
