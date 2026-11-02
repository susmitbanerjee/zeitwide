import { readdirSync, readFileSync } from "node:fs";
import { join } from "node:path";

import { describe, expect, it } from "vitest";

import { DOCS } from "@/content/docs.generated";

import { allDocs, docPath, findDoc, parseDoc, parseInline, parseMarkdown } from "./docs";

const SOURCE = join(__dirname, "..", "..", "..", "docs", "site");

describe("docs", () => {
  it("are the files in docs/site, word for word (make sync-docs)", () => {
    const files = readdirSync(SOURCE).filter((name) => name.endsWith(".md"));
    expect(Object.keys(DOCS).sort()).toEqual(files.map((name) => name.replace(/\.md$/, "")).sort());
    for (const name of files) {
      expect(DOCS[name.replace(/\.md$/, "")], name).toBe(readFileSync(join(SOURCE, name), "utf8"));
    }
  });

  it("read their front matter and keep the raw file", () => {
    const doc = parseDoc(
      "x",
      "---\ntitle: T\ndescription: D: with a colon\nupdated: 2026-11-02\n---\n\n# T\n",
    );
    expect(doc).toMatchObject({ title: "T", description: "D: with a colon", body: "# T\n" });
    expect(doc.raw.startsWith("---\n")).toBe(true);
    expect(() => parseDoc("x", "# no front matter")).toThrow("no front matter");
    expect(() => parseDoc("x", "---\ntitle: T\n---\nbody")).toThrow("no description");
    expect(allDocs().every((page) => page.title !== "")).toBe(true);
    expect(findDoc("index")?.title).toBe("Developer quickstart");
    expect(findDoc("nope")).toBeUndefined();
    expect(docPath("index")).toBe("/docs");
    expect(docPath("rules")).toBe("/docs/rules");
  });

  it("parse the Markdown they use into blocks", () => {
    const blocks = parseMarkdown(
      [
        "# Title",
        "",
        "Some **bold** and `code`,",
        "joined.",
        "",
        "- one",
        "  continued",
        "* two",
        "",
        "1. first",
        "2. second",
        "",
        "```bash",
        "curl x",
        "```",
        "### Small",
      ].join("\n"),
    );
    expect(blocks.map((block) => block.type)).toEqual([
      "heading",
      "paragraph",
      "list",
      "list",
      "code",
      "heading",
    ]);
    expect(blocks[1]).toEqual({
      type: "paragraph",
      text: [
        { type: "text", text: "Some " },
        { type: "strong", text: "bold" },
        { type: "text", text: " and " },
        { type: "code", text: "code" },
        { type: "text", text: ", joined." },
      ],
    });
    expect(blocks[2]).toMatchObject({ items: [[{ text: "one continued" }], [{ text: "two" }]] });
    expect(blocks[4]).toEqual({ type: "code", language: "bash", text: "curl x" });
  });

  it("only link to web pages and site paths", () => {
    expect(
      parseInline("[a](https://x.example) <https://y.example> [b](/docs) [c](javascript:alert)"),
    ).toEqual([
      { type: "link", text: "a", href: "https://x.example" },
      { type: "text", text: " " },
      { type: "link", text: "https://y.example", href: "https://y.example" },
      { type: "text", text: " " },
      { type: "link", text: "b", href: "/docs" },
      { type: "text", text: " " },
      { type: "link", text: "c", href: "#" },
    ]);
  });
});
