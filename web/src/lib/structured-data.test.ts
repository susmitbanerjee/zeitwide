import { JSDOM } from "jsdom";
import { describe, expect, it } from "vitest";

import { DECEMBER, CHAPS_WITH_HOURS } from "./fixtures.test-data";
import { answerPageModel, pricingPageModel } from "./page-models";
import { contentGraph, pageGraph } from "./structured-data";
import { checkStructuredData } from "./structured-data-check";
import { Graph } from "./structured-data-schema";

const SITE = "https://zeitwide.example";

const answer = answerPageModel(
  {
    calendar: CHAPS_WITH_HOURS,
    day: DECEMBER.days[4]!,
    band: DECEMBER.days,
    others: [],
    release: "2026.10.28.1",
    yearPublishers: ["GOV.UK"],
  },
  SITE,
);

function withNode(extra: Record<string, unknown>) {
  const graph = pageGraph(pricingPageModel(SITE), SITE);
  return { ...graph, "@graph": [...graph["@graph"], extra] };
}

describe("the JSON-LD schemas", () => {
  it("keep stable @ids for the site, the API and each calendar's dataset", () => {
    const ids = pageGraph(answer, SITE)["@graph"].map((node) => node["@id"]);
    expect(ids).toContain("https://zeitwide.example/#org");
    expect(ids).toContain("https://zeitwide.example/#website");
    expect(answer.datasetId).toBe("https://zeitwide.example/calendars/GB-CHAPS#dataset");
    const reference = contentGraph(
      { url: `${SITE}/docs/api`, title: "API reference", crumbs: [], describesApi: { name: "X" } },
      SITE,
    );
    expect(reference["@graph"].map((node) => node["@id"])).toContain(
      "https://zeitwide.example/#api",
    );
  });

  it("refuse unknown properties, so ratings, reviews and offers can't slip in", () => {
    const graph = pageGraph(pricingPageModel(SITE), SITE);
    const [organization, ...rest] = graph["@graph"];
    const rated = { ...organization, aggregateRating: { ratingValue: 5 } };
    expect(Graph.safeParse({ ...graph, "@graph": [rated, ...rest] }).success).toBe(false);
    expect(Graph.safeParse(withNode({ "@type": "Offer", "@id": `${SITE}/#offer` })).success).toBe(
      false,
    );
  });

  it("refuse a Dataset missing a required property", () => {
    const dataset = {
      "@type": "Dataset",
      "@id": `${SITE}/calendars/GB-CHAPS#dataset`,
      name: "CHAPS business days and closures",
      description: "x".repeat(60),
      url: `${SITE}/calendars/GB-CHAPS`,
      creator: { "@id": `${SITE}/#org` },
      version: "41",
      distribution: [
        { "@type": "DataDownload", encodingFormat: "text/csv", contentUrl: `${SITE}/d.csv` },
      ],
      variableMeasured: "business day status",
    };
    expect(Graph.safeParse(withNode(dataset)).success).toBe(false);
    expect(
      Graph.safeParse(
        withNode({ ...dataset, license: "https://creativecommons.org/licenses/by-nc/4.0/" }),
      ).success,
    ).toBe(true);
  });

  it("refuse duplicated nodes and links to nodes that don't exist", () => {
    const graph = pageGraph(pricingPageModel(SITE), SITE);
    const twice = { ...graph, "@graph": [...graph["@graph"], graph["@graph"][0]!] };
    expect(Graph.safeParse(twice).success).toBe(false);
    const dangling = withNode({
      "@type": "WebSite",
      "@id": `${SITE}/#other`,
      name: "Other",
      url: SITE,
      publisher: { "@id": `${SITE}/#nobody` },
    });
    expect(Graph.safeParse(dangling).success).toBe(false);
  });

  it("throw while building, so a bad graph fails the build", () => {
    expect(() => pageGraph({ ...answer, title: " " }, SITE)).toThrow();
  });
});

describe("the consistency check", () => {
  function page(graph: unknown, body: string) {
    const json = JSON.stringify(graph);
    return new JSDOM(
      `<!doctype html><body><script type="application/ld+json">${json}</script>${body}</body>`,
    ).window.document;
  }
  const shell = `<a href="/"><img src="/logo.png" alt="">Zeitwide</a>
    <a href="https://github.com/susmitbanerjee/zeitwide">GitHub</a>
    <a href="https://pypi.org/project/zeitwide/">PyPI</a>`;
  const trail = `<nav aria-label="Breadcrumb"><ol><li><a href="/">Home</a></li>
    <li><span aria-hidden="true">/</span><span>Pricing</span></li></ol></nav>`;
  const pricing = pageGraph(pricingPageModel(SITE), SITE);
  const lead = pricingPageModel(SITE).lead;

  it("passes a page that shows everything its graph says", () => {
    const document = page(pricing, `${shell}${trail}<h1>Pricing</h1><p>${lead}</p>`);
    expect(checkStructuredData(document, `${SITE}/pricing`)).toEqual([]);
  });

  it("names text, links and trails the page doesn't show", () => {
    const document = page(pricing, `<h1>Pricing</h1>`);
    const problems = checkStructuredData(document, `${SITE}/pricing`);
    expect(problems.some((p) => p.includes("is not on the page"))).toBe(true);
    expect(problems.some((p) => p.includes("is not linked from the page"))).toBe(true);
    expect(problems.some((p) => p.includes("BreadcrumbList differs"))).toBe(true);
  });

  it("names an answer that differs from the AnswerLead or the Zeitband", () => {
    const graph = pageGraph(answer, SITE);
    const body = `${shell}<h1>${answer.title}</h1><p data-answer-lead>Yes: open.</p>
      <a aria-current="date" href="/calendars/GB-CHAPS/2026-12-28">CHAPS open Mon 28 Dec 2026</a>`;
    const problems = checkStructuredData(page(graph, body), answer.url);
    expect(problems.some((p) => p.startsWith("Answer text differs"))).toBe(true);
    expect(problems.some((p) => p.startsWith("Status differs"))).toBe(true);
    expect(problems.some((p) => p.includes("is not a <time> on the page"))).toBe(true);
  });

  it("refuses pages with no graph, two graphs, or ratings", () => {
    const empty = new JSDOM("<body></body>").window.document;
    expect(checkStructuredData(empty, SITE)).toEqual(["Expected one JSON-LD script, found 0"]);
    const rated = page({ "@type": "AggregateRating" }, "");
    expect(checkStructuredData(rated, SITE)).toEqual([
      "Ratings, reviews and offers are never marked up",
    ]);
  });
});
