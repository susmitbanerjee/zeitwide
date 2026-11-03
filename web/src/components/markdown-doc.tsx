import Link from "next/link";

import { Code } from "@/components/ui/primitives";
import { parseMarkdown, type Block, type Inline } from "@/lib/docs";

/** A doc's Markdown body as React elements: no HTML strings, so nothing in a doc runs. */
export function MarkdownDoc({ body }: { body: string }) {
  return (
    <div className="flex max-w-3xl flex-col gap-4">
      {parseMarkdown(body).map((block, index) => (
        <BlockView key={index} block={block} />
      ))}
    </div>
  );
}

function BlockView({ block }: { block: Block }) {
  switch (block.type) {
    case "heading":
      if (block.level === 1) {
        return (
          <h1 className="text-4xl font-bold tracking-[-0.02em]">
            <InlineView parts={block.text} />
          </h1>
        );
      }
      return block.level === 2 ? (
        <h2 className="pt-4 text-xl leading-7 font-semibold">
          <InlineView parts={block.text} />
        </h2>
      ) : (
        <h3 className="font-semibold">
          <InlineView parts={block.text} />
        </h3>
      );
    case "paragraph":
      return (
        <p className="text-ink-muted">
          <InlineView parts={block.text} />
        </p>
      );
    case "list":
      return (
        <ul className="flex list-disc flex-col gap-1 pl-5 text-ink-muted">
          {block.items.map((item, index) => (
            <li key={index}>
              <InlineView parts={item} />
            </li>
          ))}
        </ul>
      );
    case "code":
      return <Code>{block.text}</Code>;
  }
}

function InlineView({ parts }: { parts: Inline[] }) {
  return parts.map((part, index) => {
    switch (part.type) {
      case "text":
        return <span key={index}>{part.text}</span>;
      case "code":
        return (
          <code key={index} className="font-mono text-[0.92em]">
            {part.text}
          </code>
        );
      case "strong":
        return (
          <strong key={index} className="font-semibold text-ink">
            {part.text}
          </strong>
        );
      case "link":
        return part.href.startsWith("/") ? (
          <Link key={index} href={part.href}>
            {part.text}
          </Link>
        ) : (
          <a key={index} href={part.href} rel="noopener noreferrer">
            {part.text}
          </a>
        );
    }
  });
}
