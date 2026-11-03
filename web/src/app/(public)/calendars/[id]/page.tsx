import type { Metadata } from "next";

import { publicPageMetadata, renderPublicPage } from "@/components/public/render";
import { getCalendars } from "@/lib/api-server";

export const ensureStatic = "navigation";

export async function generateStaticParams(): Promise<{ id: string }[]> {
  const { calendars } = await getCalendars();
  return calendars.map((calendar) => ({ id: calendar.id }));
}

export async function generateMetadata({
  params,
}: PageProps<"/calendars/[id]">): Promise<Metadata> {
  return publicPageMetadata(`/calendars/${(await params).id}`);
}

export default async function CalendarPage({ params }: PageProps<"/calendars/[id]">) {
  return renderPublicPage(`/calendars/${(await params).id}`);
}
