import type { Metadata } from "next";

import { Developers } from "./developers";

export const metadata: Metadata = { title: "Keys and webhooks" };

export default function DevelopersPage() {
  return <Developers />;
}
