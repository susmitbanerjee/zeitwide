import { BOTS_VERSION, type Family } from "./bots";

export const DATASET = "zeitwide_edge";

/**
 * One request, as stored in Analytics Engine. Nothing here identifies a person: no IP, no
 * User-Agent, no query string, no full path. Bots are labelled by vendor and family only.
 */
export interface EdgeEvent {
  readonly template: string;
  readonly status: number;
  /** cf-cache-status from the origin fetch (HIT, MISS, DYNAMIC, ...), "NONE" when absent. */
  readonly cacheStatus: string;
  readonly family: Family;
  readonly vendor: string;
  readonly verified: boolean;
  readonly markdown: boolean;
  /** ISO country code from Cloudflare, "XX" when unknown. */
  readonly country: string;
  readonly bytes: number;
}

/**
 * Column order is the SQL schema (daily-report.ts reads it back): blob1 template, blob2 status,
 * blob3 cache status, blob4 family, blob5 vendor, blob6 verified, blob7 markdown, blob8 country,
 * blob9 bots.json version; double1 bytes, double2 status. The index (the sampling key) is the
 * family, so rare bot families are not sampled away under the busy human one.
 */
export function toDataPoint(event: EdgeEvent): AnalyticsEngineDataPoint {
  return {
    indexes: [event.family],
    blobs: [
      event.template,
      String(event.status),
      event.cacheStatus,
      event.family,
      event.vendor,
      event.verified ? "1" : "0",
      event.markdown ? "1" : "0",
      event.country,
      BOTS_VERSION,
    ],
    doubles: [event.bytes, event.status],
  };
}

/**
 * The response with its body counted as it streams, calling `done` with the byte count once the
 * body ends (or the client goes away). A known Content-Length is used as is, without counting.
 */
export function countBytes(
  response: Response,
  done: (bytes: number) => void,
): { response: Response; finished: Promise<void> } {
  const length = Number(response.headers.get("content-length"));
  if (
    response.body === null ||
    (Number.isInteger(length) && length >= 0 && response.headers.has("content-length"))
  ) {
    done(response.body === null ? 0 : length);
    return { response, finished: Promise.resolve() };
  }
  let bytes = 0;
  const counter = new TransformStream<Uint8Array, Uint8Array>({
    transform(chunk, controller) {
      bytes += chunk.byteLength;
      controller.enqueue(chunk);
    },
  });
  const finished = response.body.pipeTo(counter.writable).then(
    () => done(bytes),
    () => done(bytes),
  );
  return { response: new Response(counter.readable, response), finished };
}
