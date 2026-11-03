import { RuleTester } from "eslint";
import { describe, expect, it } from "vitest";

import { isVagueLinkText, linkTextRule } from "./link-text.mjs";

RuleTester.describe = describe;
RuleTester.it = it;

const tester = new RuleTester({
  languageOptions: { parserOptions: { ecmaFeatures: { jsx: true } } },
});

tester.run("link-text", linkTextRule, {
  valid: [
    '<Link href="/x">CHAPS reopens Tue 29 Dec 2026</Link>',
    '<a href="/x">T2 has 6 closing days in 2027, 3 on weekdays</a>',
    "<Link href={path}>{sentence}</Link>",
    '<button type="button">Click to copy</button>',
  ],
  invalid: [
    { code: '<Link href="/x">here</Link>', errors: [{ messageId: "vague" }] },
    { code: '<a href="/x">Read more</a>', errors: [{ messageId: "vague" }] },
    { code: '<Link href="/x"><span>Learn more</span> →</Link>', errors: [{ messageId: "vague" }] },
    { code: '<a href="/x">Click for the calendar</a>', errors: [{ messageId: "vague" }] },
    {
      code: '<Link href="/x" aria-label="Click here">{icon}</Link>',
      errors: [{ messageId: "vague" }],
    },
  ],
});

describe("isVagueLinkText", () => {
  it("ignores case, punctuation and spacing", () => {
    expect(isVagueLinkText("  Read more…  ")).toBe(true);
    expect(isVagueLinkText("HERE!")).toBe(true);
    expect(isVagueLinkText("Where CHAPS reopens")).toBe(false);
  });
});
