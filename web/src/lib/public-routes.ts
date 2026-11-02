import { isIsoDate } from "./format";
import { isYear } from "./public-pages";

/**
 * Which public page a path names. The HTML pages and the Markdown route both use it, so a page
 * exists in one format exactly when it exists in the other.
 */
export type PublicRoute =
  | { type: "calendars" }
  | { type: "calendar"; id: string }
  | { type: "year"; id: string; year: number }
  | { type: "answer"; id: string; date: string }
  | { type: "compare"; pair: string; year: number }
  | { type: "sources" }
  | { type: "pricing" }
  | { type: "doc"; slug: string };

export function matchPublicPath(path: string): PublicRoute | null {
  const parts = path
    .split("/")
    .filter((part) => part !== "")
    .map(decodeSegment);
  if (parts.some((part) => part === null)) {
    return null;
  }
  const [first, second, third, ...rest] = parts as string[];
  if (rest.length > 0) {
    return null;
  }
  if (first === "calendars") {
    if (second === undefined) {
      return { type: "calendars" };
    }
    if (third === undefined) {
      return { type: "calendar", id: second };
    }
    if (isYear(third)) {
      return { type: "year", id: second, year: Number(third) };
    }
    return isIsoDate(third) ? { type: "answer", id: second, date: third } : null;
  }
  if (first === "compare" && second !== undefined && third !== undefined && isYear(third)) {
    return { type: "compare", pair: second, year: Number(third) };
  }
  if (first === "docs" && third === undefined) {
    return { type: "doc", slug: second ?? "index" };
  }
  if (second === undefined && (first === "sources" || first === "pricing")) {
    return { type: first };
  }
  return null;
}

function decodeSegment(segment: string): string | null {
  try {
    return decodeURIComponent(segment);
  } catch {
    return null;
  }
}

/** The Markdown twin of a canonical page URL. */
export function markdownUrl(canonical: string): string {
  const url = new URL(canonical);
  url.pathname = `${url.pathname.replace(/\/$/, "")}.md`;
  return url.toString();
}
