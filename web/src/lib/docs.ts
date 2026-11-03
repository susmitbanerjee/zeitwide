import { DOCS } from "@/content/docs.generated";

/**
 * The public docs, from docs/site/*.md (synced into a module by scripts/sync-docs.mjs). The
 * same files render /docs/{slug}, are served raw at /docs/{slug}.md, ship in the open library
 * as zeitwide.docs and go into llms-full.txt.
 */

export interface Doc {
  /** "index" is /docs; any other slug is /docs/{slug}. */
  slug: string;
  title: string;
  description: string;
  /** ISO date the page last changed. */
  updated: string;
  /** The Markdown after the front matter. */
  body: string;
  /** The file exactly as written, front matter included. */
  raw: string;
}

const FRONT_MATTER = /^---\n([\s\S]*?)\n---\n/;

/** Reads `key: value` front matter; the docs use plain one-line values only. */
export function parseDoc(slug: string, raw: string): Doc {
  const match = FRONT_MATTER.exec(raw);
  if (match === null) {
    throw new Error(`docs/site/${slug}.md has no front matter`);
  }
  const fields = new Map(
    match[1]!.split("\n").map((line) => {
      const separator = line.indexOf(":");
      return [line.slice(0, separator).trim(), line.slice(separator + 1).trim()] as const;
    }),
  );
  const field = (name: string): string => {
    const value = fields.get(name);
    if (value === undefined || value === "") {
      throw new Error(`docs/site/${slug}.md has no ${name}`);
    }
    return value;
  };
  return {
    slug,
    title: field("title"),
    description: field("description"),
    updated: field("updated"),
    body: raw.slice(match[0].length).trimStart(),
    raw,
  };
}

export function allDocs(): Doc[] {
  return Object.entries(DOCS).map(([slug, raw]) => parseDoc(slug, raw));
}

export function findDoc(slug: string): Doc | undefined {
  const raw = DOCS[slug];
  return raw === undefined ? undefined : parseDoc(slug, raw);
}

export function docPath(slug: string): string {
  return slug === "index" ? "/docs" : `/docs/${slug}`;
}

// ---- A Markdown subset, as blocks the page renders with React (never as HTML strings) ----

export type Inline =
  | { type: "text"; text: string }
  | { type: "code"; text: string }
  | { type: "strong"; text: string }
  | { type: "link"; text: string; href: string };

export type Block =
  | { type: "heading"; level: 1 | 2 | 3; text: Inline[] }
  | { type: "paragraph"; text: Inline[] }
  | { type: "list"; items: Inline[][] }
  | { type: "code"; language: string; text: string };

/**
 * Headings, paragraphs, bullet lists, fenced code, inline code, bold and links: everything the
 * docs use. Anything else reads as plain text, so nothing in a doc can become markup.
 */
export function parseMarkdown(body: string): Block[] {
  const lines = body.replace(/\r\n/g, "\n").split("\n");
  const blocks: Block[] = [];
  let index = 0;
  while (index < lines.length) {
    const line = lines[index]!;
    const fence = /^```(\w*)\s*$/.exec(line);
    if (fence !== null) {
      const code: string[] = [];
      index++;
      while (index < lines.length && !lines[index]!.startsWith("```")) {
        code.push(lines[index]!);
        index++;
      }
      blocks.push({ type: "code", language: fence[1] ?? "", text: code.join("\n") });
      index++;
      continue;
    }
    const heading = /^(#{1,3}) (.+)$/.exec(line);
    if (heading !== null) {
      blocks.push({
        type: "heading",
        level: heading[1]!.length as 1 | 2 | 3,
        text: parseInline(heading[2]!),
      });
      index++;
      continue;
    }
    if (/^[-*] /.test(line)) {
      const items: string[] = [];
      while (
        index < lines.length &&
        (/^[-*] /.test(lines[index]!) || /^ {2,}\S/.test(lines[index]!))
      ) {
        const current = lines[index]!;
        if (/^[-*] /.test(current)) {
          items.push(current.slice(2));
        } else {
          items[items.length - 1] += ` ${current.trim()}`;
        }
        index++;
      }
      blocks.push({ type: "list", items: items.map(parseInline) });
      continue;
    }
    if (/^\d+\. /.test(line)) {
      const items: string[] = [];
      while (
        index < lines.length &&
        (/^\d+\. /.test(lines[index]!) || /^ {2,}\S/.test(lines[index]!))
      ) {
        const current = lines[index]!;
        if (/^\d+\. /.test(current)) {
          items.push(current.replace(/^\d+\. /, ""));
        } else {
          items[items.length - 1] += ` ${current.trim()}`;
        }
        index++;
      }
      blocks.push({ type: "list", items: items.map(parseInline) });
      continue;
    }
    if (line.trim() === "") {
      index++;
      continue;
    }
    const paragraph: string[] = [];
    while (
      index < lines.length &&
      lines[index]!.trim() !== "" &&
      !/^(#{1,3} |```|[-*] |\d+\. )/.test(lines[index]!)
    ) {
      paragraph.push(lines[index]!.trim());
      index++;
    }
    blocks.push({ type: "paragraph", text: parseInline(paragraph.join(" ")) });
  }
  return blocks;
}

const INLINE = /`([^`]+)`|\*\*([^*]+)\*\*|\[([^\]]+)\]\(([^)\s]+)\)|<(https?:\/\/[^>\s]+)>/g;

export function parseInline(text: string): Inline[] {
  const parts: Inline[] = [];
  let last = 0;
  for (const match of text.matchAll(INLINE)) {
    if (match.index > last) {
      parts.push({ type: "text", text: text.slice(last, match.index) });
    }
    if (match[1] !== undefined) {
      parts.push({ type: "code", text: match[1] });
    } else if (match[2] !== undefined) {
      parts.push({ type: "strong", text: match[2] });
    } else if (match[3] !== undefined && match[4] !== undefined) {
      parts.push({ type: "link", text: match[3], href: safeHref(match[4]) });
    } else if (match[5] !== undefined) {
      parts.push({ type: "link", text: match[5], href: safeHref(match[5]) });
    }
    last = match.index + match[0].length;
  }
  if (last < text.length) {
    parts.push({ type: "text", text: text.slice(last) });
  }
  return parts;
}

/** Only web links and site paths; anything else (javascript:, data:) becomes inert. */
function safeHref(href: string): string {
  return /^(https?:\/\/|\/)/.test(href) ? href : "#";
}
