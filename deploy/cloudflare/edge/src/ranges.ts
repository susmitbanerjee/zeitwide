import { RANGE_SOURCES } from "./bots";
import { contains, parseAddress, parseNetwork, type Network } from "./ip";

/** What the cron stores per range list in KV. */
export interface StoredRanges {
  readonly source: string;
  readonly fetchedAt: string;
  /** The vendor's own creationTime, when it gives one. */
  readonly createdAt: string | null;
  readonly prefixes: readonly string[];
}

export const rangeKey = (id: string): string => `ranges:v1:${id}`;

const MEMORY_TTL_MS = 60 * 60 * 1000;
/** A list that is missing (not refreshed yet) or unreadable is asked for again after this. */
const RETRY_MS = 60 * 1000;
const memory = new Map<
  string,
  { readonly expiresAt: number; readonly networks: readonly Network[] }
>();

/**
 * Whether `ip` is inside any of the named range lists. Lists are read from KV once an hour per
 * isolate, so a verified fetcher costs a KV read at most hourly. A list the cron has not stored
 * yet verifies nothing.
 */
export async function inRanges(
  kv: KVNamespace,
  ids: readonly string[],
  ip: string | null,
  now: number = Date.now(),
): Promise<boolean> {
  const address = ip === null ? null : parseAddress(ip);
  if (address === null || ids.length === 0) {
    return false;
  }
  for (const id of ids) {
    const networks = await networksFor(kv, id, now);
    if (networks.some((network) => contains(network, address))) {
      return true;
    }
  }
  return false;
}

async function networksFor(kv: KVNamespace, id: string, now: number): Promise<readonly Network[]> {
  const cached = memory.get(id);
  if (cached !== undefined && now < cached.expiresAt) {
    return cached.networks;
  }
  let stored: StoredRanges | null;
  try {
    stored = await kv.get<StoredRanges>(rangeKey(id), { type: "json", cacheTtl: 3600 });
  } catch {
    // KV over its daily read limit, or down: verify nothing, and ask again in a minute.
    memory.set(id, { expiresAt: now + RETRY_MS, networks: [] });
    return [];
  }
  const networks = (stored?.prefixes ?? []).flatMap((prefix) => {
    const network = parseNetwork(prefix);
    return network === null ? [] : [network];
  });
  memory.set(id, { expiresAt: now + (stored === null ? RETRY_MS : MEMORY_TTL_MS), networks });
  return networks;
}

/** Test hook: forget what this isolate has read. */
export function forgetRanges(): void {
  memory.clear();
}

/** Pulls the prefixes out of a vendor's JSON (the Google/OpenAI shape every vendor here uses). */
export function parseVendorRanges(body: unknown): { createdAt: string | null; prefixes: string[] } {
  if (
    typeof body !== "object" ||
    body === null ||
    !Array.isArray((body as { prefixes?: unknown }).prefixes)
  ) {
    throw new Error("no prefixes array");
  }
  const { prefixes, creationTime } = body as { prefixes: unknown[]; creationTime?: unknown };
  const valid = prefixes.flatMap((entry) => {
    if (typeof entry !== "object" || entry === null) {
      return [];
    }
    const { ipv4Prefix, ipv6Prefix } = entry as { ipv4Prefix?: unknown; ipv6Prefix?: unknown };
    const prefix = typeof ipv4Prefix === "string" ? ipv4Prefix : ipv6Prefix;
    return typeof prefix === "string" && parseNetwork(prefix) !== null ? [prefix] : [];
  });
  if (valid.length === 0) {
    throw new Error("no valid prefixes");
  }
  return { createdAt: typeof creationTime === "string" ? creationTime : null, prefixes: valid };
}

export interface RefreshResult {
  readonly id: string;
  readonly ok: boolean;
  readonly count?: number;
  readonly error?: string;
}

/**
 * The daily cron: fetches every vendor list and stores it. A list that fails to fetch or parse
 * keeps its previous value, so one bad response never un-verifies a vendor.
 */
export async function refreshRanges(
  kv: KVNamespace,
  now: Date = new Date(),
  sources: Readonly<Record<string, string>> = RANGE_SOURCES,
): Promise<RefreshResult[]> {
  return Promise.all(
    Object.entries(sources).map(async ([id, source]): Promise<RefreshResult> => {
      try {
        const response = await fetch(source, {
          headers: { accept: "application/json", "user-agent": "zeitwide-edge/1 (range refresh)" },
          signal: AbortSignal.timeout(10_000),
        });
        if (!response.ok) {
          throw new Error(`HTTP ${response.status}`);
        }
        const { createdAt, prefixes } = parseVendorRanges(await response.json());
        const stored: StoredRanges = { source, fetchedAt: now.toISOString(), createdAt, prefixes };
        await kv.put(rangeKey(id), JSON.stringify(stored));
        return { id, ok: true, count: prefixes.length };
      } catch (error) {
        return { id, ok: false, error: error instanceof Error ? error.message : String(error) };
      }
    }),
  );
}
