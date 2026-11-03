import { JSDOM } from "jsdom";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it } from "vitest";

import type { RangeDay } from "@/api/client";
import { CHAPS_WITH_HOURS, DECEMBER, FEDNOW, FEDWIRE, T2 } from "@/lib/fixtures.test-data";
import { toMarkdown, unescapeText } from "@/lib/markdown";
import {
  answerPageModel,
  calendarPageModel,
  calendarsPageModel,
  comparePageModel,
  pricingPageModel,
  sourcesPageModel,
  yearPageModel,
  type PageModel,
} from "@/lib/page-models";
import { compareYear, summarizeYear } from "@/lib/public-pages";
import { checkStructuredData } from "@/lib/structured-data-check";
import { PublicShell } from "@/components/site-chrome";

import { PublicPageView } from "./public-page";

/**
 * One snapshot per public template, rendered from fixture data both ways: static HTML (what a
 * crawler gets with no JavaScript) and Markdown. Both come from the same model, and the checks
 * name what each must always contain.
 */

const SITE = "https://zeitwide.example";
const RELEASE = "2026.10.28.1";
const days = DECEMBER.days;
const boxingDay = days[4]!;
// T2 stays open on the UK's Boxing Day substitute.
const t2Days = days.map((day): RangeDay => {
  if (day.date !== "2026-12-28") {
    return day;
  }
  return {
    date: day.date,
    is_open: true,
    decided_by: "weekend_rule",
    sources: [],
    ...(day.next_business_day === undefined ? {} : { next_business_day: day.next_business_day }),
  };
});
const GOV_UK = {
  publisher: "GOV.UK",
  url: "https://www.gov.uk/bank-holidays",
  lastRead: "2026-10-31T06:10:00Z",
  calendars: ["CHAPS"],
};

function render(model: PageModel): { html: string; visible: string; markdown: string } {
  const html = renderToStaticMarkup(<PublicPageView model={model} />);
  // The JSON-LD repeats the question and answer; order checks are about what people read.
  const visible = html.replace(/<script[^>]*>.*?<\/script>/gs, "");
  return { html, visible, markdown: toMarkdown(model) };
}

/** The lead as HTML text: React escapes entities and may split text with comments. */
function htmlLead(html: string): string {
  const match = /<p data-answer-lead=""[^>]*>(.*?)<\/p>/s.exec(html);
  return (match?.[1] ?? "")
    .replace(/<!-- -->/g, "")
    .replace(/&#x27;/g, "'")
    .replace(/&quot;/g, '"')
    .replace(/&amp;/g, "&");
}

function expectInOrder(text: string, needles: string[]) {
  const positions = needles.map((needle) => text.indexOf(needle));
  expect(
    positions.every((index) => index >= 0),
    `missing one of ${needles.join(" | ")}`,
  ).toBe(true);
  expect([...positions].sort((a, b) => a - b)).toEqual(positions);
}

/** The page in its header and footer, as served, checked against its own JSON-LD. */
function expectStructuredDataShown(model: PageModel) {
  const html = renderToStaticMarkup(
    <PublicShell>
      <PublicPageView model={model} />
    </PublicShell>,
  );
  const { document } = new JSDOM(`<!doctype html><body>${html}</body>`).window;
  expect(checkStructuredData(document, model.url)).toEqual([]);
}

function expectParity({ html, markdown }: { html: string; markdown: string }, model: PageModel) {
  // The Markdown's first paragraph after the h1 is the HTML's lead, word for word.
  const [heading, lead] = markdown.split("\n\n");
  expect(heading).toBe(`# ${model.title}`);
  expect(unescapeText(lead ?? "")).toBe(htmlLead(html));
  expect(markdown).not.toMatch(/!\[|<img|Sign in/);
  expect(new TextEncoder().encode(markdown).byteLength).toBeLessThan(20 * 1024);
}

describe("answer page", () => {
  const model = answerPageModel(
    {
      calendar: CHAPS_WITH_HOURS,
      day: boxingDay,
      band: days,
      others: [
        { calendar: T2, day: t2Days[4] },
        { calendar: FEDWIRE, day: undefined },
        {
          calendar: FEDNOW,
          day: { ...boxingDay, is_open: true, decided_by: "weekend_rule", sources: [] },
        },
      ],
      release: RELEASE,
      yearPublishers: ["GOV.UK"],
    },
    SITE,
  );
  const page = render(model);
  const lead =
    "No: Monday 28 December 2026 is not a CHAPS business day, because CHAPS is closed for Boxing Day (substitute day), according to GOV.UK; CHAPS reopens on Tuesday 29 December 2026 at 06:00 Europe/London (Zeitwide release 2026.10.28.1).";
  const cite =
    "Zeitwide (2026). GB-CHAPS on 2026-12-28. Release 2026.10.28.1. https://zeitwide.example/calendars/GB-CHAPS/2026-12-28";

  it("puts the question, AnswerLead, band, tables, sources, citation and FAQ in order", () => {
    expectInOrder(page.visible, [
      "<h1",
      "Is Monday 28 December 2026 a CHAPS business day?",
      lead,
      "28 days of CHAPS",
      "<caption",
      "Other calendars on Mon 28 Dec 2026",
      "T2 open Mon 28 Dec 2026",
      "https://www.gov.uk/bank-holidays",
      cite,
      "<details",
    ]);
    expect(page.visible.match(/<details/g)).toHaveLength(3);
    expect(page.visible).toContain("CHAPS reopens Tue 29 Dec 2026");
    expect(page.html).toMatchSnapshot();
  });

  it("gives the same facts as Markdown: h1, lead, tables, sources, then the citation", () => {
    expectParity(page, model);
    expectInOrder(page.markdown, [
      "# Is Monday 28 December 2026 a CHAPS business day?",
      lead,
      "| Date | CHAPS |",
      "| Calendar | Status | Why |",
      "## Sources",
      "- GOV.UK: <https://www.gov.uk/bank-holidays> (Read 31 Oct 2026, 06:10 UTC)",
      "## Cite",
      cite,
    ]);
    expect(page.markdown).toContain(
      "CHAPS closed Mon 28 Dec 2026: Boxing Day (substitute day) (this date)",
    );
    expect(page.markdown).toContain("CHAPS revision 41, published 28 Oct 2026, 07:29 UTC.");
    expect(page.markdown).toMatchSnapshot();
  });

  it("marks up only what it shows: the question, its answer, the FAQ and the trail", () => {
    expectStructuredDataShown(model);
    expect(page.html).toContain('<time dateTime="2026-10-28T07:29:18.079663Z">');
  });
});

describe("year page", () => {
  const model = yearPageModel(
    {
      calendar: CHAPS_WITH_HOURS,
      summary: summarizeYear(CHAPS_WITH_HOURS, 2026, days),
      release: RELEASE,
      neighbours: [{ year: 2027, closures: [days[8]!], weekdayClosures: 1 }],
      comparisons: [
        { calendar: T2, comparison: compareYear(CHAPS_WITH_HOURS, days, T2, t2Days, 2026) },
      ],
    },
    SITE,
  );
  const page = render(model);

  it("lists every closure as a table, with fact-stating links", () => {
    expect(page.html).toContain("CHAPS closures in 2026</h1>");
    expect(page.html).toContain("CHAPS has 3 closing days in 2026, 3 on weekdays.");
    expect(page.html).toContain("<caption");
    expect(page.html).toContain("CHAPS closed Fri 25 Dec 2026: Christmas Day");
    expect(page.html).toContain("CHAPS has 1 closing day in 2027, 1 on a weekday");
    expect(page.html).toContain('href="/compare/EU-T2-vs-GB-CHAPS/2026"');
    expect(page.html).toContain("CHAPS and T2 share 2 closing days in 2026");
    expect(page.html).toMatchSnapshot();
  });

  it("gives the same facts as Markdown", () => {
    expectParity(page, model);
    expect(page.markdown).toContain(
      "| [CHAPS closed Fri 25 Dec 2026: Christmas Day](https://zeitwide.example/calendars/GB-CHAPS/2026-12-25) | Christmas Day | Published |",
    );
    expect(page.markdown).toMatchSnapshot();
  });

  it("is about the calendar's dataset, by @id", () => {
    expectStructuredDataShown(model);
  });
});

describe("calendar page", () => {
  const model = calendarPageModel(
    {
      calendar: CHAPS_WITH_HOURS,
      today: "2026-12-24",
      todayAnswer: days[0],
      release: RELEASE,
      nextClosures: days.filter((day) => day.decided_by === "entry"),
      years: [summarizeYear(CHAPS_WITH_HOURS, 2026, days)],
      sources: [GOV_UK],
    },
    SITE,
  );
  const page = render(model);

  it("says what the calendar is, its hours, its next closures and sources", () => {
    expect(page.html).toContain("CHAPS (GB-CHAPS) is a payment rail on Europe/London time.");
    expect(page.html).toContain("CHAPS is open today, Thursday 24 December 2026");
    expect(page.html).toContain("Customer payments");
    expect(page.html).toContain("The next 3 closures on CHAPS after 24 December 2026");
    expect(page.html).toMatchSnapshot();
  });

  it("gives the same facts as Markdown", () => {
    expectParity(page, model);
    expect(page.markdown).toContain("| Customer payments | 17:40 |");
    expect(page.markdown).toContain(
      "[CHAPS as CSV (text/csv)](https://zeitwide.example/downloads/GB-CHAPS.csv)",
    );
    expect(page.markdown).toMatchSnapshot();
  });

  it("describes the dataset with the coverage, revision, license and files it shows", () => {
    expectStructuredDataShown(model);
    expect(page.html).toContain('href="/downloads/GB-CHAPS.ics"');
    expect(page.html).toContain('<time dateTime="2026-01-01">1 January 2026</time>');
  });
});

describe("compare page", () => {
  const model = comparePageModel(
    {
      a: T2,
      b: CHAPS_WITH_HOURS,
      year: 2026,
      comparison: compareYear(T2, t2Days, CHAPS_WITH_HOURS, days, 2026),
      release: RELEASE,
    },
    SITE,
  );
  const page = render(model);

  it("opens with the one-sentence difference and a side-by-side table", () => {
    expect(page.html).toContain("T2 vs CHAPS: closures in 2026</h1>");
    expect(page.html).toContain(
      "In 2026 T2 closes on 2 days and CHAPS on 3; they share Christmas Day and New Year&#x27;s Day.",
    );
    expect(page.html).toContain("<caption");
    expect(page.html).toMatchSnapshot();
  });

  it("gives the same facts as Markdown", () => {
    expectParity(page, model);
    expect(page.markdown).toContain("| Date | T2 | CHAPS |");
    expect(page.markdown).toMatchSnapshot();
  });

  it("is about both calendars' datasets, by @id", () => {
    expectStructuredDataShown(model);
  });
});

describe("list pages", () => {
  it("renders the index, sources and pricing both ways", () => {
    const summary = summarizeYear(CHAPS_WITH_HOURS, 2026, days);
    for (const model of [
      calendarsPageModel(
        [
          { calendar: CHAPS_WITH_HOURS, summary },
          { calendar: FEDNOW, summary: { year: 2026, closures: [], weekdayClosures: 0 } },
        ],
        RELEASE,
        SITE,
      ),
      sourcesPageModel([GOV_UK], RELEASE, [2026, 2027], SITE),
      pricingPageModel(SITE),
    ]) {
      const page = render(model);
      expectParity(page, model);
      expectStructuredDataShown(model);
      expect(page.markdown).toMatchSnapshot(model.type);
    }
  });
});
