import Link from "next/link";

import { CopyButton } from "@/components/copy-button";
import { JsonLd } from "@/components/json-ld";
import { Breadcrumbs } from "@/components/site-chrome";
import { Card, Code, SectionTitle } from "@/components/ui/primitives";
import { cn } from "@/lib/cn";
import {
  revisionSentence,
  type Cell,
  type DatasetBlock,
  type DatedText,
  type LinkList,
  type PageModel,
  type RevisionFact,
  type SourceItem,
  type TableBlock,
} from "@/lib/page-models";
import { pageGraph } from "@/lib/structured-data";

import { ZeitbandTable } from "./zeitband-table";

/**
 * Every public data page, rendered from its PageModel: the same model toMarkdown renders, so the
 * HTML and Markdown state the same facts. Answer pages add the Zeitband, citation, FAQ and API
 * example in the order the answer template sets. No fact needs JavaScript. The JSON-LD graph is
 * built from the same model, so it states only what the page shows.
 */
export function PublicPageView({ model }: { model: PageModel }) {
  const answer = model.type === "answer" ? model : null;
  return (
    <article className="flex flex-col gap-10">
      <JsonLd data={pageGraph(model, new URL(model.url).origin)} />
      <header className="flex flex-col gap-4">
        <Breadcrumbs items={model.crumbs} />
        <h1 className="max-w-4xl text-3xl leading-tight font-bold tracking-[-0.02em] sm:text-4xl">
          {model.title}
        </h1>
        <p
          data-answer-lead=""
          className={cn(
            "max-w-4xl text-xl leading-snug font-semibold",
            answer !== null && "border-l-4 pl-4",
            answer !== null && (answer.open ? "border-ink" : "border-closed"),
          )}
        >
          {model.lead}
        </p>
        {model.notes.map((note) => (
          <p key={note} className="max-w-3xl text-ink-muted">
            {note}
          </p>
        ))}
        {answer !== null && answer.neighbours.length > 0 && (
          <LinkItems links={answer.neighbours} className="flex flex-wrap gap-x-6 gap-y-1 text-sm" />
        )}
        {model.type === "calendar" && model.today !== null && <LinkItems links={[model.today]} />}
      </header>

      {answer !== null && (
        <section aria-labelledby="zeitband" className="flex flex-col gap-3">
          <SectionTitle id="zeitband">{answer.band.heading}</SectionTitle>
          <ZeitbandTable band={answer.band} />
        </section>
      )}

      {model.tables.map((block) => (
        <ModelTable key={block.id} block={block} />
      ))}

      {"bullets" in model && model.bullets.length > 0 && (
        <Card className="flex max-w-2xl flex-col gap-4 p-6">
          <h2 className="text-xl font-semibold">{model.bulletsHeading}</h2>
          <ul className="flex list-disc flex-col gap-1 pl-5">
            {model.bullets.map((bullet) => (
              <li key={bullet}>{bullet}</li>
            ))}
          </ul>
        </Card>
      )}

      {model.type === "calendar" && <DatasetSection dataset={model.dataset} />}

      {model.lists.map((list) => (
        <LinkListSection key={list.id} list={list} />
      ))}

      {model.type !== "pricing" && model.type !== "calendars" && (
        <section aria-labelledby="sources" className="flex flex-col gap-4">
          <SectionTitle id="sources">Sources</SectionTitle>
          {model.sources.length > 0 ? (
            <SourceList sources={model.sources} />
          ) : (
            model.noSources !== undefined && <p className="text-ink-muted">{model.noSources}</p>
          )}
          {model.release !== undefined && (
            <p className="text-sm text-ink-muted">
              Zeitwide release <span className="font-mono">{model.release}</span>.
              {answer !== null && (
                <>
                  {" "}
                  <Link href={answer.evidence.href} prefetch={false}>
                    {answer.evidence.text}
                  </Link>
                </>
              )}
            </p>
          )}
          {answer !== null && (
            <p className="text-sm text-ink-muted">
              <RevisionText fact={answer.revision} />
            </p>
          )}
          {model.cite !== undefined && <CiteBlock text={model.cite} />}
        </section>
      )}

      {model.faqs.length > 0 && (
        <section aria-labelledby="faq" className="flex flex-col gap-3">
          <SectionTitle id="faq">Questions people ask</SectionTitle>
          <div className="flex flex-col divide-y divide-line border-y border-line">
            {model.faqs.map((faq) => (
              <details key={faq.question} className="py-3">
                <summary className="flex min-h-11 cursor-pointer items-center font-semibold">
                  {faq.question}
                </summary>
                <p className="text-ink-muted">{faq.answer}</p>
              </details>
            ))}
          </div>
        </section>
      )}

      {answer !== null && (
        <section aria-labelledby="code" className="flex flex-col gap-3">
          <SectionTitle id="code">The same answer from the API</SectionTitle>
          <Code>{`${answer.api.curl}\n\n${answer.api.json}`}</Code>
          <p className="text-sm text-ink-muted">
            Agents get it from the <span className="font-mono">is_business_day</span> MCP tool.{" "}
            <Link href="/docs">The developer quickstart covers keys, MCP and webhooks</Link>
          </p>
        </section>
      )}
    </article>
  );
}

const cellClass = "border-b border-line px-3 py-3 align-top";

function ModelTable({ block }: { block: TableBlock }) {
  return (
    <section aria-labelledby={block.id} className="flex flex-col gap-3">
      <SectionTitle id={block.id}>{block.heading}</SectionTitle>
      <div className="overflow-x-auto">
        <table className="w-full border-collapse text-left text-sm">
          <caption className="pb-2 text-left text-ink-muted">{block.caption}</caption>
          <thead>
            <tr>
              {block.columns.map((column) => (
                <th
                  key={column}
                  scope="col"
                  className="border-b border-line px-3 py-2 font-semibold"
                >
                  {column}
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            {block.rows.length === 0 ? (
              <tr>
                <td colSpan={block.columns.length} className={`${cellClass} text-ink-muted`}>
                  {block.empty}
                </td>
              </tr>
            ) : (
              block.rows.map((row) => (
                <tr key={row[0]?.text}>
                  {row.map((cell, index) =>
                    index === 0 ? (
                      <th key={index} scope="row" className={`${cellClass} text-left font-normal`}>
                        <CellContent cell={cell} />
                      </th>
                    ) : (
                      <td key={index} className={cellClass}>
                        <CellContent cell={cell} />
                      </td>
                    ),
                  )}
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>
    </section>
  );
}

function CellContent({ cell }: { cell: Cell }) {
  const closed = cell.kind === "closed";
  if (cell.href === undefined) {
    return <span className={cn(closed && "font-semibold text-closed-ink")}>{cell.text}</span>;
  }
  if (/^https?:/.test(cell.href)) {
    return (
      <a href={cell.href} rel="noopener noreferrer" target="_blank" className="font-semibold">
        {cell.text}
      </a>
    );
  }
  return (
    <Link
      href={cell.href}
      prefetch={false}
      className={cn("inline-flex min-h-6 items-center", closed && "font-semibold text-closed-ink")}
    >
      {cell.text}
    </Link>
  );
}

function LinkItems({
  links,
  className = "flex flex-col gap-1",
}: {
  links: { text: string; href: string }[];
  className?: string;
}) {
  return (
    <ul className={className}>
      {links.map((link) => (
        <li key={link.href}>
          <Link href={link.href} prefetch={false} className="inline-flex min-h-11 items-center">
            {link.text}
          </Link>
        </li>
      ))}
    </ul>
  );
}

function LinkListSection({ list }: { list: LinkList }) {
  return (
    <section aria-labelledby={list.id} className="flex flex-col gap-3">
      <SectionTitle id={list.id}>{list.heading}</SectionTitle>
      <LinkItems links={list.links} />
    </section>
  );
}

function SourceList({ sources }: { sources: SourceItem[] }) {
  return (
    <ul className="flex flex-col gap-2">
      {sources.map((source) => (
        <li key={source.url} className="flex flex-col">
          <a href={source.url} rel="noopener noreferrer" target="_blank" className="font-semibold">
            {source.publisher}
          </a>
          <span className="text-sm text-ink-muted">
            {source.read}
            {source.detail === undefined ? "" : `. ${source.detail}`}
          </span>
        </li>
      ))}
    </ul>
  );
}

function Dated({ value }: { value: DatedText }) {
  return <time dateTime={value.iso}>{value.text}</time>;
}

/** The revision sentence, with its date marked up for the JSON-LD's dateModified. */
function RevisionText({ fact }: { fact: RevisionFact }) {
  if (fact.published === null) {
    return <>{revisionSentence(fact)}</>;
  }
  return (
    <>
      {fact.calendarName} revision {fact.revision}, published <Dated value={fact.published} />.
    </>
  );
}

/** What the calendar's files hold, the dates they cover, their revision, license and links. */
function DatasetSection({ dataset }: { dataset: DatasetBlock }) {
  return (
    <section aria-labelledby="data" className="flex flex-col gap-3">
      <SectionTitle id="data">{dataset.heading}</SectionTitle>
      <p className="max-w-3xl">{dataset.statusNote}</p>
      <p className="max-w-3xl text-ink-muted">
        {dataset.coverage === null ? (
          dataset.coverageNote
        ) : (
          <>
            Covers <Dated value={dataset.coverage.from} /> to <Dated value={dataset.coverage.to} />.
          </>
        )}{" "}
        <RevisionText fact={dataset.revision} /> Licensed under{" "}
        <a href={dataset.license.url} rel="license noopener noreferrer" target="_blank">
          {dataset.license.name}
        </a>
        .
      </p>
      <ul className="flex flex-col gap-1">
        {dataset.downloads.map((download) => (
          // A file, not a page: a plain link, so the router never tries to render it.
          <li key={download.href}>
            <a href={download.href} className="inline-flex min-h-11 items-center">
              {download.text}
            </a>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** Citation text shown in full (readable without JavaScript), with a copy button. */
function CiteBlock({ text }: { text: string }) {
  return (
    <figure className="m-0 flex flex-col gap-2">
      <figcaption className="text-sm font-semibold">Cite this answer</figcaption>
      <pre className="overflow-x-auto rounded-md border border-line bg-surface-sunken p-3 font-mono text-[13px] leading-5 whitespace-pre-wrap">
        {text}
      </pre>
      <div>
        <CopyButton text={text} label="Copy citation" />
      </div>
    </figure>
  );
}
