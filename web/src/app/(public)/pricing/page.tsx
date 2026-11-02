import type { Metadata } from "next";

import { publicPageMetadata, renderPublicPage } from "@/components/public/render";

export function generateMetadata(): Promise<Metadata> {
  return publicPageMetadata("/pricing");
}

export default function PricingPage() {
  return renderPublicPage("/pricing");
}
