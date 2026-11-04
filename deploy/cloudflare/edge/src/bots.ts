import catalogue from "./bots.json";

export const BOT_FAMILIES = [
  "training",
  "search",
  "user-fetch",
  "agent",
  "monitor",
  "unknown",
] as const;
export type BotFamily = (typeof BOT_FAMILIES)[number];
/** Every request is labelled: a bot family from bots.json, or a person. */
export type Family = BotFamily | "human";

export interface BotEntry {
  readonly token: string;
  readonly vendor: string;
  readonly family: BotFamily;
  /** Range lists (keys of `ranges` in bots.json) that verify this bot. Empty: never verified. */
  readonly ranges: readonly string[];
}

export interface Classification {
  readonly family: Family;
  /** Vendor from bots.json; "unknown" for an unlisted bot, "" for a person. */
  readonly vendor: string;
  /** The matched bots.json token (never the full User-Agent); "" when none matched. */
  readonly token: string;
  readonly ranges: readonly string[];
}

/** The vendors whose published IP ranges decide `verified`. */
export const VERIFIABLE_VENDORS: ReadonlySet<string> = new Set([
  "openai",
  "anthropic",
  "perplexity",
  "google",
  "apple",
]);

export const BOTS_VERSION: string = catalogue.version;
export const RANGE_SOURCES: Readonly<Record<string, string>> = catalogue.ranges;

const bots: readonly (BotEntry & { readonly needle: string })[] = catalogue.bots.map((bot) => {
  if (!(BOT_FAMILIES as readonly string[]).includes(bot.family)) {
    throw new Error(`bots.json: ${bot.token} has unknown family ${bot.family}`);
  }
  for (const id of bot.ranges) {
    if (!(id in catalogue.ranges)) {
      throw new Error(`bots.json: ${bot.token} names unknown range list ${id}`);
    }
  }
  return { ...bot, family: bot.family as BotFamily, needle: bot.token.toLowerCase() };
});
const unknownBot = new RegExp(catalogue.unknownPattern, "i");

const HUMAN: Classification = { family: "human", vendor: "", token: "", ranges: [] };
const UNKNOWN: Classification = { family: "unknown", vendor: "unknown", token: "", ranges: [] };

/** Labels a request by its User-Agent alone; `verified` needs the IP as well (ranges.ts). */
export function classify(userAgent: string | null): Classification {
  if (userAgent === null || userAgent.trim() === "") {
    return UNKNOWN;
  }
  const ua = userAgent.toLowerCase();
  const bot = bots.find((entry) => ua.includes(entry.needle));
  if (bot !== undefined) {
    const ranges = VERIFIABLE_VENDORS.has(bot.vendor) ? bot.ranges : [];
    return { family: bot.family, vendor: bot.vendor, token: bot.token, ranges };
  }
  return unknownBot.test(ua) ? UNKNOWN : HUMAN;
}
