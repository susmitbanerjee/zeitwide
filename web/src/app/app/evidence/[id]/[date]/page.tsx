import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { isIsoDate } from "@/lib/format";

import { Evidence } from "./evidence";

export const metadata: Metadata = { title: "Evidence" };

export default async function EvidencePage({ params }: PageProps<"/app/evidence/[id]/[date]">) {
  const { id, date } = await params;
  if (!isIsoDate(date) || !/^[A-Z0-9]+(-[A-Z0-9]+)*$/.test(id)) {
    notFound();
  }
  return <Evidence calendarId={id} date={date} />;
}
