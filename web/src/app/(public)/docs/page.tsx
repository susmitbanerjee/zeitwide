import type { Metadata } from "next";

import { docMetadata, DocPage } from "@/components/public/doc-page";

export function generateMetadata(): Metadata {
  return docMetadata("index");
}

/** C3: the developer quickstart, from docs/site/index.md. */
export default function DocsPage() {
  return <DocPage slug="index" />;
}
