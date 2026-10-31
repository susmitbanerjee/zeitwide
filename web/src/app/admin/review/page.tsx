import type { Metadata } from "next";

import { ReviewQueue } from "./queue";

export const metadata: Metadata = { title: "Review queue" };

export default function ReviewQueuePage() {
  return <ReviewQueue />;
}
