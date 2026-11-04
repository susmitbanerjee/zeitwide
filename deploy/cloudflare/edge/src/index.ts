import { classify, type Classification } from "./bots";
import { pushDailyReport } from "./daily-report";
import type { Env } from "./env";
import { prefersMarkdown } from "./negotiate";
import { pathInfo } from "./paths";
import { inRanges, refreshRanges } from "./ranges";
import { aiReferral, referralCookie } from "./referral";
import schedules from "./schedules.json";
import { countBytes, toDataPoint, type EdgeEvent } from "./telemetry";

const RETRY_AFTER_S = 60;

interface Labels extends Classification {
  readonly verified: boolean;
}

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    // If this Worker throws, the request goes to the origin as if the Worker were not there.
    ctx.passThroughOnException();
    const url = new URL(request.url);
    const path = pathInfo(url.pathname);
    const labels = await label(request, env);

    if (labels.family !== "human" && !labels.verified && (await overLimit(env, request))) {
      const limited = new Response("Too many requests. Slow down to 60 a minute.\n", {
        status: 429,
        headers: {
          "retry-after": String(RETRY_AFTER_S),
          "cache-control": "no-store",
          "content-type": "text/plain; charset=utf-8",
        },
      });
      return finish(limited, {
        env,
        ctx,
        request,
        labels,
        template: path.template,
        markdown: false,
      });
    }

    const markdown =
      request.method === "GET" && path.negotiable && prefersMarkdown(request.headers.get("accept"));
    const target = markdown ? markdownUrl(url) : url;
    const upstream = await fetch(new Request(originUrl(target, env), request));
    const response = new Response(upstream.body, upstream);
    if (markdown || isHtml(response)) {
      appendVary(response.headers, "Accept");
    }
    if (request.method === "GET" && labels.family === "human" && isHtml(response)) {
      const vendor = aiReferral(request.headers.get("referer"), url);
      if (vendor !== null) {
        response.headers.append("set-cookie", referralCookie(vendor));
      }
    }
    const servedMarkdown = markdown && isMarkdown(response);
    return finish(response, {
      env,
      ctx,
      request,
      labels,
      template: path.template,
      markdown: servedMarkdown,
    });
  },

  async scheduled(controller: ScheduledController, env: Env): Promise<void> {
    if (controller.cron === schedules.ranges) {
      const results = await refreshRanges(env.RANGES, new Date(controller.scheduledTime));
      console.log(JSON.stringify({ job: "ranges", results }));
      if (results.every((result) => !result.ok)) {
        throw new Error("every range list failed to refresh");
      }
    } else if (controller.cron === schedules.report) {
      const result = await pushDailyReport(env, new Date(controller.scheduledTime));
      console.log(JSON.stringify({ job: "report", ...result }));
    } else {
      throw new Error(`no job for cron ${controller.cron}`);
    }
  },
} satisfies ExportedHandler<Env>;

async function label(request: Request, env: Env): Promise<Labels> {
  const bot = classify(request.headers.get("user-agent"));
  const verified =
    bot.ranges.length > 0 &&
    (await inRanges(env.RANGES, bot.ranges, request.headers.get("cf-connecting-ip")));
  return { ...bot, verified };
}

/** Unverified bots: 60 a minute per IP. If the limiter is missing or fails, let the request in. */
async function overLimit(env: Env, request: Request): Promise<boolean> {
  const ip = request.headers.get("cf-connecting-ip");
  if (env.BOT_LIMIT === undefined || ip === null) {
    return false;
  }
  try {
    const { success } = await env.BOT_LIMIT.limit({ key: ip });
    return !success;
  } catch {
    return false;
  }
}

function markdownUrl(url: URL): URL {
  const twin = new URL(url);
  twin.pathname = `${url.pathname.replace(/\/+$/, "")}.md`;
  return twin;
}

/** In production the request goes to its own host (the zone's origin); locally, to ORIGIN. */
function originUrl(url: URL, env: Env): string {
  if (env.ORIGIN === undefined || env.ORIGIN === "") {
    return url.toString();
  }
  return new URL(`${url.pathname}${url.search}`, env.ORIGIN).toString();
}

const contentType = (response: Response): string =>
  (response.headers.get("content-type") ?? "").toLowerCase();
const isHtml = (response: Response): boolean => contentType(response).startsWith("text/html");
const isMarkdown = (response: Response): boolean =>
  contentType(response).startsWith("text/markdown");

function appendVary(headers: Headers, field: string): void {
  const vary = headers.get("vary");
  const fields = (vary ?? "").split(",").map((part) => part.trim().toLowerCase());
  if (!fields.includes(field.toLowerCase()) && !fields.includes("*")) {
    headers.set("vary", vary === null || vary.trim() === "" ? field : `${vary}, ${field}`);
  }
}

interface FinishContext {
  readonly env: Env;
  readonly ctx: ExecutionContext;
  readonly request: Request;
  readonly labels: Labels;
  readonly template: string;
  readonly markdown: boolean;
}

/** Adds the debug header if asked, and writes the data point once the body has been sent. */
function finish(
  response: Response,
  { env, ctx, request, labels, template, markdown }: FinishContext,
): Response {
  if (env.DEBUG_LABELS === "1") {
    response.headers.set(
      "x-zw-edge",
      `family=${labels.family}; vendor=${labels.vendor || "-"}; verified=${labels.verified}; markdown=${markdown}`,
    );
  }
  const cf = request.cf as { country?: unknown } | undefined;
  const country = typeof cf?.country === "string" ? cf.country : "XX";
  const counted = countBytes(response, (bytes) => {
    const event: EdgeEvent = {
      template,
      status: response.status,
      cacheStatus: response.headers.get("cf-cache-status") ?? "NONE",
      family: labels.family,
      vendor: labels.vendor,
      verified: labels.verified,
      markdown,
      country,
      bytes,
    };
    try {
      env.EDGE_EVENTS.writeDataPoint(toDataPoint(event));
    } catch {
      // Telemetry never fails a request.
    }
  });
  ctx.waitUntil(counted.finished);
  return counted.response;
}
