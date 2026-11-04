import { describe, expect, it } from "vitest";

import { CHAPS, DECEMBER } from "./fixtures.test-data";
import {
  absoluteUrl,
  answerDescription,
  answerPath,
  answerQuestion,
  answerSentence,
  serializeJsonLd,
} from "./seo";

const boxingDay = DECEMBER.days[4]!;
const christmasEve = DECEMBER.days[0]!;
const saturday = DECEMBER.days[2]!;

describe("answer copy", () => {
  it("asks and answers the page's question", () => {
    expect(answerQuestion(CHAPS, "2026-12-28")).toBe(
      "Is Monday 28 December 2026 a CHAPS business day?",
    );
    expect(answerSentence(CHAPS, boxingDay)).toBe(
      "No. CHAPS is closed on Monday 28 December 2026: Boxing Day (substitute day).",
    );
    expect(answerSentence(CHAPS, christmasEve)).toMatch(/^Yes\. CHAPS is open/);
    expect(answerSentence(CHAPS, saturday)).toMatch(/is a weekend day/);
  });

  it("describes the neighbours the API gave", () => {
    expect(answerDescription(CHAPS, boxingDay)).toBe(
      "No. CHAPS is closed on Monday 28 December 2026: Boxing Day (substitute day). Next business day: Tue 29 Dec 2026. Previous: Thu 24 Dec 2026. Backed by official sources.",
    );
    expect(answerDescription(CHAPS, christmasEve)).not.toContain("Next business day");
  });
});

describe("urls and structured data", () => {
  it("builds paths and absolute URLs", () => {
    expect(answerPath("GB-CHAPS", "2026-12-28")).toBe("/calendars/GB-CHAPS/2026-12-28");
    expect(absoluteUrl("https://zeitwide.com", "/calendars")).toBe(
      "https://zeitwide.com/calendars",
    );
  });

  it("can never close the script element it is written into", () => {
    const text = serializeJsonLd({
      reason: `</script><script>alert(1)</script> & ${String.fromCharCode(0x2028, 0x2029)}`,
    });
    expect(text).not.toMatch(new RegExp(`[<>&${String.fromCharCode(0x2028, 0x2029)}]`));
    expect(JSON.parse(text)).toEqual({
      reason: `</script><script>alert(1)</script> & ${String.fromCharCode(0x2028, 0x2029)}`,
    });
  });
});
