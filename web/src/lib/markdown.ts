import {
  revisionSentence,
  type Cell,
  type DatasetBlock,
  type PageLink,
  type PageModel,
  type SourceItem,
  type TableBlock,
} from "./page-models";

/**
 * A page model as GitHub-flavoured Markdown, for AI systems and anyone who wants the facts without
 * the page: the h1, the lead as the first paragraph, the tables, the questions, the sources with
 * full URLs, then the citation. No navigation, footer or images. Built from the same model as the
 * HTML, never converted from it.
 */
export function toMarkdown(model: PageModel): string {
  const blocks: string[] = [`# ${escapeText(model.title)}`, escapeText(model.lead)];
  if (model.notes.length > 0) {
    blocks.push(model.notes.map(escapeText).join("\n\n"));
  }
  if (model.type === "answer") {
    blocks.push(
      section(model.band.heading, [
        escapeText(model.band.caption),
        table(
          ["Date", model.band.calendarName],
          model.band.days.map((day) => [
            { text: day.label, href: day.href },
            { text: day.selected ? `${day.fact} (this date)` : day.fact },
          ]),
          model.url,
        ),
      ]),
    );
  }
  for (const block of model.tables) {
    blocks.push(section(block.heading, [escapeText(block.caption), tableBlock(block, model.url)]));
  }
  if ("bullets" in model && model.bullets.length > 0) {
    blocks.push(
      section(model.bulletsHeading ?? "Included", [
        model.bullets.map((b) => `- ${escapeText(b)}`).join("\n"),
      ]),
    );
  }
  if (model.type === "calendar") {
    blocks.push(datasetSection(model.dataset, model.url));
  }
  for (const list of model.lists) {
    blocks.push(section(list.heading, [links(list.links, model.url)]));
  }
  if (model.faqs.length > 0) {
    blocks.push(
      section(
        "Questions people ask",
        model.faqs.map((faq) => `**${escapeText(faq.question)}**\n${escapeText(faq.answer)}`),
      ),
    );
  }
  blocks.push(section("Sources", sourcesBlock(model)));
  if (model.cite !== undefined) {
    blocks.push(section("Cite", [escapeText(model.cite)]));
  }
  return `${blocks.filter((block) => block !== "").join("\n\n")}\n`;
}

function sourcesBlock(model: PageModel): string[] {
  const lines: string[] = [];
  if (model.sources.length > 0) {
    lines.push(model.sources.map(sourceLine).join("\n"));
  } else if (model.noSources !== undefined) {
    lines.push(escapeText(model.noSources));
  }
  if (model.release !== undefined) {
    lines.push(`Zeitwide release ${escapeText(model.release)}.`);
  }
  if (model.type === "answer") {
    lines.push(escapeText(revisionSentence(model.revision)));
  }
  return lines;
}

function datasetSection(dataset: DatasetBlock, base: string): string {
  const coverage =
    dataset.coverage === null
      ? dataset.coverageNote
      : `Covers ${dataset.coverage.from.text} to ${dataset.coverage.to.text}.`;
  return section(dataset.heading, [
    escapeText(dataset.statusNote),
    escapeText(
      `${coverage} ${revisionSentence(dataset.revision)} Licensed under ${dataset.license.name}: `,
    ) + `<${dataset.license.url}>`,
    links(dataset.downloads, base),
  ]);
}

function sourceLine(source: SourceItem): string {
  const detail = source.detail === undefined ? "" : `. ${escapeText(source.detail)}`;
  return `- ${escapeText(source.publisher)}: <${source.url}> (${escapeText(source.read)})${detail}`;
}

function section(heading: string, body: string[]): string {
  return [`## ${escapeText(heading)}`, ...body.filter((part) => part !== "")].join("\n\n");
}

function links(items: PageLink[], base: string): string {
  return items.map((link) => `- ${linkText(link.text, link.href, base)}`).join("\n");
}

function tableBlock(block: TableBlock, base: string): string {
  if (block.rows.length === 0) {
    return escapeText(block.empty);
  }
  return table(block.columns, block.rows, base);
}

function table(columns: string[], rows: Cell[][], base: string): string {
  const line = (cells: string[]) => `| ${cells.join(" | ")} |`;
  return [
    line(columns.map(escapeCell)),
    line(columns.map(() => "---")),
    ...rows.map((row) =>
      line(
        row.map((cell) =>
          cell.href === undefined ? escapeCell(cell.text) : linkText(cell.text, cell.href, base),
        ),
      ),
    ),
  ].join("\n");
}

function linkText(text: string, href: string, base: string): string {
  return `[${escapeCell(text)}](${new URL(href, base).toString()})`;
}

/** Escapes the characters Markdown would read as formatting. */
export function escapeText(text: string): string {
  return text.replace(/([\\`*_[\]<>|])/g, "\\$1");
}

function escapeCell(text: string): string {
  return escapeText(text).replace(/\r?\n/g, " ");
}

/** Markdown back to the plain text it states, for comparing facts with the HTML. */
export function unescapeText(text: string): string {
  return text.replace(/\\([\\`*_[\]<>|])/g, "$1");
}
