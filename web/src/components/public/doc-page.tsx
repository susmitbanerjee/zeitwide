import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";

import { MarkdownDoc } from "@/components/markdown-doc";
import { buttonVariants } from "@/components/ui/button";
import { docPath, findDoc } from "@/lib/docs";
import { env } from "@/lib/env";
import { formatDate } from "@/lib/format";
import { markdownUrl } from "@/lib/public-routes";
import { absoluteUrl } from "@/lib/seo";

/** One page of docs/site, rendered from its Markdown. */
export function DocPage({ slug }: { slug: string }) {
  const doc = findDoc(slug);
  if (doc === undefined) {
    notFound();
  }
  return (
    <article className="flex flex-col gap-6">
      <MarkdownDoc body={doc.body} />
      <p className="text-sm text-ink-muted">Updated {formatDate(doc.updated, "plain")}.</p>
      {slug === "index" && (
        <div className="flex flex-wrap gap-2">
          <Link
            href="/app/developers"
            prefetch={false}
            className={buttonVariants({ variant: "primary" })}
          >
            Get a free API key
          </Link>
          <Link href="/docs/api" className={buttonVariants({ variant: "outline" })}>
            API reference for every /v1 endpoint
          </Link>
        </div>
      )}
    </article>
  );
}

export function docMetadata(slug: string): Metadata {
  const doc = findDoc(slug);
  if (doc === undefined) {
    return { title: "Not found", robots: { index: false } };
  }
  const canonical = absoluteUrl(env.siteUrl, docPath(slug));
  return {
    title: doc.title,
    description: doc.description,
    alternates: { canonical, types: { "text/markdown": markdownUrl(canonical) } },
  };
}
