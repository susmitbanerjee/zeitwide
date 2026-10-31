import type { Metadata } from "next";

import { Sources } from "./sources";

export const metadata: Metadata = { title: "Source health" };

export default function SourcesPage() {
  return <Sources />;
}
