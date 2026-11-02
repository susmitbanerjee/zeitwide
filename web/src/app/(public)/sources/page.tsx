import type { Metadata } from "next";

import { publicPageMetadata, renderPublicPage } from "@/components/public/render";

export function generateMetadata(): Promise<Metadata> {
  return publicPageMetadata("/sources");
}

export default function SourcesPage() {
  return renderPublicPage("/sources");
}
