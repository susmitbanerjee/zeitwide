import { Graph } from "./structured-data-schema";

/**
 * Checks a rendered page's JSON-LD against what the page shows, the way a reader would see it:
 * every text value is in the page's text, every URL is a link or image on it (or the page itself),
 * every date is a <time> on it, and an answer page's question, answer, status and FAQ match the
 * visible AnswerLead, Zeitband and FAQ word for word. Returns the problems found, none when the
 * graph and the page agree. Works on any DOM Document (jsdom in tests and CI).
 */

// Keys whose values are vocabulary or identifiers, not content shown to readers.
const NOT_CONTENT = new Set(["@context", "@type", "@id"]);
const DATE_KEYS = new Set(["dateModified", "temporalCoverage"]);
const BANNED = /\b(Rating|AggregateRating|Review|Offer|AggregateOffer)\b/;

type Json = null | boolean | number | string | Json[] | { [key: string]: Json };

export function checkStructuredData(document: Document, pageUrl: string): string[] {
  const scripts = [...document.querySelectorAll('script[type="application/ld+json"]')];
  if (scripts.length !== 1) {
    return [`Expected one JSON-LD script, found ${scripts.length}`];
  }
  const raw = scripts[0]?.textContent ?? "";
  if (BANNED.test(raw) || /"(aggregateRating|review|offers)"/.test(raw)) {
    return ["Ratings, reviews and offers are never marked up"];
  }
  let data: Json;
  try {
    data = JSON.parse(raw) as Json;
  } catch {
    return ["The JSON-LD is not JSON"];
  }
  const parsed = Graph.safeParse(data);
  if (!parsed.success) {
    return parsed.error.issues.map((issue) => `Schema: ${issue.path.join(".")} ${issue.message}`);
  }
  const page = visible(document, pageUrl);
  const problems: string[] = [];
  walk(data, [], (path, key, value) => {
    const where = path.join(".");
    if (DATE_KEYS.has(key)) {
      for (const part of value.split("/")) {
        if (!page.times.has(part)) {
          problems.push(`${where}: ${part} is not a <time> on the page`);
        }
      }
    } else if (/^https?:\/\//.test(value)) {
      if (!page.urls.has(value)) {
        problems.push(`${where}: ${value} is not linked from the page`);
      }
    } else if (!page.text.includes(squash(value))) {
      problems.push(`${where}: "${value}" is not on the page`);
    }
  });
  problems.push(...answerProblems(parsed.data, document), ...faqProblems(parsed.data, document));
  problems.push(...breadcrumbProblems(parsed.data, document, pageUrl));
  return problems;
}

interface Visible {
  text: string;
  urls: Set<string>;
  times: Set<string>;
}

function visible(document: Document, pageUrl: string): Visible {
  const body = document.body.cloneNode(true) as HTMLElement;
  body.querySelectorAll("script, style, template").forEach((node) => node.remove());
  const urls = new Set([new URL(pageUrl).toString()]);
  document.querySelectorAll("a[href], img[src]").forEach((element) => {
    const link = element.getAttribute("href") ?? element.getAttribute("src") ?? "";
    urls.add(new URL(link, pageUrl).toString());
  });
  const times = new Set(
    [...document.querySelectorAll("time[datetime]")].map((t) => t.getAttribute("datetime") ?? ""),
  );
  return { text: squash(body.textContent ?? ""), urls, times };
}

function walk(
  value: Json,
  path: string[],
  visit: (path: string[], key: string, value: string) => void,
): void {
  if (Array.isArray(value)) {
    value.forEach((item, index) => walk(item, [...path, String(index)], visit));
  } else if (typeof value === "object" && value !== null) {
    for (const [key, item] of Object.entries(value)) {
      if (NOT_CONTENT.has(key)) {
        continue;
      }
      if (typeof item === "string") {
        visit([...path, key], key, item);
      } else {
        walk(item, [...path, key], visit);
      }
    }
  }
}

function answerProblems(graph: Graph, document: Document): string[] {
  const question = graph["@graph"].find((node) => node["@type"] === "Question");
  if (question === undefined || question["@type"] !== "Question") {
    return [];
  }
  const problems: string[] = [];
  const lead = squash(document.querySelector("[data-answer-lead]")?.textContent ?? "");
  const answer = question.acceptedAnswer.text;
  if (squash(answer) !== lead) {
    problems.push(`Answer text differs from the AnswerLead: "${answer}" vs "${lead}"`);
  }
  const heading = squash(document.querySelector("h1")?.textContent ?? "");
  if (squash(question.name) !== heading) {
    problems.push(`Question differs from the h1: "${question.name}" vs "${heading}"`);
  }
  // The Zeitband's cell for this date says open or closed; the answer must say the same.
  const cell = squash(document.querySelector('a[aria-current="date"]')?.textContent ?? "");
  const cellOpen = / open /.test(` ${cell} `);
  const answerOpen = answer.startsWith("Yes");
  if (!/ (open|closed) /.test(` ${cell} `) || cellOpen !== answerOpen) {
    problems.push(`Status differs: the answer says "${answer.slice(0, 3)}", the table "${cell}"`);
  }
  return problems;
}

function faqProblems(graph: Graph, document: Document): string[] {
  const faq = graph["@graph"].find((node) => node["@type"] === "FAQPage");
  if (faq === undefined || faq["@type"] !== "FAQPage") {
    return [];
  }
  const shown = [...document.querySelectorAll('section[aria-labelledby="faq"] details')].map(
    (details) => ({
      question: squash(details.querySelector("summary")?.textContent ?? ""),
      answer: squash(details.querySelector("p")?.textContent ?? ""),
    }),
  );
  if (shown.length !== faq.mainEntity.length) {
    return [`FAQPage has ${faq.mainEntity.length} questions; the page shows ${shown.length}`];
  }
  return faq.mainEntity.flatMap((item, index) => {
    const visibleItem = shown[index];
    return visibleItem?.question === squash(item.name) &&
      visibleItem.answer === squash(item.acceptedAnswer.text)
      ? []
      : [`FAQ ${index + 1} differs from the page: "${item.name}"`];
  });
}

function breadcrumbProblems(graph: Graph, document: Document, pageUrl: string): string[] {
  const list = graph["@graph"].find((node) => node["@type"] === "BreadcrumbList");
  const shown = [...document.querySelectorAll('nav[aria-label="Breadcrumb"] li')].map((item) => {
    const link = item.querySelector("a")?.getAttribute("href");
    // The separator between crumbs is decoration, hidden from readers.
    const label = item.cloneNode(true) as Element;
    label.querySelectorAll('[aria-hidden="true"]').forEach((node) => node.remove());
    return {
      name: squash(label.textContent ?? ""),
      item: new URL(link ?? pageUrl, pageUrl).toString(),
    };
  });
  if (list === undefined || list["@type"] !== "BreadcrumbList") {
    return shown.length > 0 ? ["The page shows breadcrumbs but has no BreadcrumbList"] : [];
  }
  const marked = list.itemListElement.map((entry) => ({
    name: squash(entry.name),
    item: entry.item,
  }));
  return JSON.stringify(marked) === JSON.stringify(shown)
    ? []
    : [`BreadcrumbList differs from the visible trail: ${JSON.stringify(shown)}`];
}

/** Whitespace collapsed, so markup line breaks don't count as differences. */
function squash(text: string): string {
  return text.replace(/\s+/g, " ").trim();
}
