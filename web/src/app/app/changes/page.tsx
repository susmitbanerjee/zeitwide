import type { Metadata } from "next";

import { Changes } from "./changes";

export const metadata: Metadata = { title: "Changes" };

export default function ChangesPage() {
  return <Changes />;
}
