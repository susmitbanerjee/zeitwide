import type { Metadata } from "next";
import { notFound } from "next/navigation";

import { Review } from "./review";

export const metadata: Metadata = { title: "Review a change" };

export default async function ReviewPage({ params }: PageProps<"/admin/review/[id]">) {
  const { id } = await params;
  if (!/^[1-9]\d{0,17}$/.test(id)) {
    notFound();
  }
  return <Review candidateId={Number(id)} />;
}
