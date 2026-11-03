import type { Metadata } from "next";

import { docMetadata, DocPage } from "@/components/public/doc-page";
import { allDocs } from "@/lib/docs";

/** Every doc but the index, which is /docs itself; any other slug is a 404 (see DocPage). */
export function generateStaticParams(): { slug: string }[] {
  return allDocs()
    .filter((doc) => doc.slug !== "index")
    .map((doc) => ({ slug: doc.slug }));
}

export async function generateMetadata({ params }: PageProps<"/docs/[slug]">): Promise<Metadata> {
  return docMetadata((await params).slug);
}

export default async function DocSlugPage({ params }: PageProps<"/docs/[slug]">) {
  return <DocPage slug={(await params).slug} />;
}
