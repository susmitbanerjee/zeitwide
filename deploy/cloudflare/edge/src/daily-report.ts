import type { Env } from "./env";
import { DATASET } from "./telemetry";

const DAY_MS = 24 * 60 * 60 * 1000;

/** The UTC day before `now`: [start, end). */
export function yesterday(now: Date): { day: string; start: Date; end: Date } {
  const end = new Date(Date.UTC(now.getUTCFullYear(), now.getUTCMonth(), now.getUTCDate()));
  const start = new Date(end.getTime() - DAY_MS);
  return { day: start.toISOString().slice(0, 10), start, end };
}

const sqlTime = (date: Date): string => date.toISOString().slice(0, 19).replace("T", " ");

/** Counts per label set for one day. `_sample_interval` undoes Analytics Engine's sampling. */
export function dailySql(start: Date, end: Date): string {
  return `SELECT blob1 AS template, blob2 AS status, blob4 AS family, blob5 AS vendor,
  blob6 AS verified, blob7 AS markdown,
  SUM(_sample_interval) AS requests, SUM(_sample_interval * double1) AS bytes
FROM ${DATASET}
WHERE timestamp >= toDateTime('${sqlTime(start)}') AND timestamp < toDateTime('${sqlTime(end)}')
GROUP BY template, status, family, vendor, verified, markdown
ORDER BY requests DESC
LIMIT 5000
FORMAT JSON`;
}

export interface DailyRow {
  readonly template: string;
  readonly status: string;
  readonly family: string;
  readonly vendor: string;
  readonly verified: boolean;
  readonly markdown: boolean;
  readonly requests: number;
  readonly bytes: number;
}

/** Rows from the SQL API's JSON (numbers may come back as strings). */
export function parseRows(body: unknown): DailyRow[] {
  const data = (body as { data?: unknown } | null)?.data;
  if (!Array.isArray(data)) {
    throw new Error("Analytics Engine answered without a data array");
  }
  return data.map((raw: Record<string, unknown>) => ({
    template: String(raw.template ?? ""),
    status: String(raw.status ?? ""),
    family: String(raw.family ?? ""),
    vendor: String(raw.vendor ?? ""),
    verified: String(raw.verified) === "1",
    markdown: String(raw.markdown) === "1",
    requests: Math.round(Number(raw.requests ?? 0)),
    bytes: Math.round(Number(raw.bytes ?? 0)),
  }));
}

/**
 * Loki push body: one stream per family, one line per label set, all stamped at noon of the day
 * they count. Re-running the report for a day sends identical entries, which Loki drops as
 * duplicates, so a retry never double counts.
 */
export function lokiPush(rows: readonly DailyRow[], day: string, environment: string): object {
  const timestamp = String(BigInt(Date.parse(`${day}T12:00:00Z`)) * 1_000_000n);
  const byFamily = new Map<string, [string, string][]>();
  for (const row of rows) {
    const line = JSON.stringify({ day, ...row });
    const values = byFamily.get(row.family) ?? [];
    values.push([timestamp, line]);
    byFamily.set(row.family, values);
  }
  return {
    streams: [...byFamily].map(([family, values]) => ({
      stream: { service_name: "zeitwide-edge", env: environment, kind: "daily", family },
      values,
    })),
  };
}

/** The nightly cron: yesterday's Analytics Engine counts to Grafana Cloud Loki. */
export async function pushDailyReport(env: Env, now: Date): Promise<{ day: string; rows: number }> {
  const { CF_ACCOUNT_ID, CF_ANALYTICS_TOKEN, LOKI_PUSH_URL, LOKI_USER, LOKI_TOKEN } = env;
  if (!CF_ACCOUNT_ID || !CF_ANALYTICS_TOKEN || !LOKI_PUSH_URL || !LOKI_USER || !LOKI_TOKEN) {
    throw new Error(
      "daily report: set CF_ACCOUNT_ID, CF_ANALYTICS_TOKEN, LOKI_PUSH_URL, LOKI_USER, LOKI_TOKEN",
    );
  }
  const { day, start, end } = yesterday(now);
  const query = await fetch(
    `https://api.cloudflare.com/client/v4/accounts/${encodeURIComponent(CF_ACCOUNT_ID)}/analytics_engine/sql`,
    {
      method: "POST",
      headers: { authorization: `Bearer ${CF_ANALYTICS_TOKEN}` },
      body: dailySql(start, end),
      signal: AbortSignal.timeout(20_000),
    },
  );
  if (!query.ok) {
    throw new Error(`Analytics Engine SQL API: HTTP ${query.status}`);
  }
  const rows = parseRows(await query.json());
  if (rows.length === 0) {
    return { day, rows: 0 };
  }
  const push = await fetch(LOKI_PUSH_URL, {
    method: "POST",
    headers: {
      "content-type": "application/json",
      authorization: `Basic ${btoa(`${LOKI_USER}:${LOKI_TOKEN}`)}`,
    },
    body: JSON.stringify(lokiPush(rows, day, env.ENVIRONMENT)),
    signal: AbortSignal.timeout(20_000),
  });
  if (!push.ok) {
    throw new Error(`Loki push: HTTP ${push.status}`);
  }
  return { day, rows: rows.length };
}
