interface MediaRange {
  readonly type: string;
  readonly subtype: string;
  readonly q: number;
  readonly order: number;
}

/** How well a media range matches a type: 2 exact, 1 type/*, 0 wildcard, -1 not at all. */
function specificity(range: MediaRange, type: string, subtype: string): number {
  if (range.type === type && range.subtype === subtype) {
    return 2;
  }
  if (range.type === type && range.subtype === "*") {
    return 1;
  }
  return range.type === "*" && range.subtype === "*" ? 0 : -1;
}

function parseAccept(header: string): MediaRange[] {
  return header.split(",").flatMap((part, order) => {
    const [media = "", ...params] = part.split(";").map((piece) => piece.trim().toLowerCase());
    const [type, subtype] = media.split("/");
    if (type === undefined || subtype === undefined || type === "" || subtype === "") {
      return [];
    }
    const qParam = params.find((param) => param.startsWith("q="));
    const q = qParam === undefined ? 1 : Number(qParam.slice(2));
    return Number.isFinite(q) && q >= 0 && q <= 1 ? [{ type, subtype, q, order }] : [];
  });
}

/** RFC 9110: a type's quality comes from the most specific range that matches it. */
function quality(ranges: readonly MediaRange[], type: string, subtype: string) {
  let best: { q: number; specificity: number; order: number } = {
    q: 0,
    specificity: -1,
    order: Infinity,
  };
  for (const range of ranges) {
    const s = specificity(range, type, subtype);
    if (s > best.specificity) {
      best = { q: range.q, specificity: s, order: range.order };
    }
  }
  return best;
}

/**
 * Whether the client prefers text/markdown to text/html. Markdown wins on a higher q. On equal q
 * it wins when it is named outright and HTML only through a wildcard ("text/markdown, *\/*"), or
 * when both are named and Markdown comes first. Browsers never send text/markdown, so they always
 * get HTML.
 */
export function prefersMarkdown(accept: string | null): boolean {
  if (accept === null) {
    return false;
  }
  const ranges = parseAccept(accept);
  const markdown = quality(ranges, "text", "markdown");
  if (markdown.specificity < 2 || markdown.q === 0) {
    return false;
  }
  const html = quality(ranges, "text", "html");
  if (markdown.q !== html.q) {
    return markdown.q > html.q;
  }
  if (html.specificity < 2) {
    return true;
  }
  return markdown.order < html.order;
}
