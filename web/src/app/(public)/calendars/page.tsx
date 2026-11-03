import type { Metadata } from "next";

import { publicPageMetadata, renderPublicPage } from "@/components/public/render";

export function generateMetadata(): Promise<Metadata> {
  return publicPageMetadata("/calendars");
}

export default function CalendarsPage() {
  return renderPublicPage("/calendars");
}
