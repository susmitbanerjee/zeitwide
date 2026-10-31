import type { Metadata } from "next";

import { SignedInShell } from "@/components/signed-in-shell";

export const metadata: Metadata = { robots: { index: false, follow: false } };

// Rendered whole on every request, with no prerendered shell: the strict CSP needs a per-request
// nonce on every script, and a shell built ahead of time can't carry one.
export const instant = false;

export default function AppLayout({ children }: LayoutProps<"/app">) {
  return <SignedInShell path="/app">{children}</SignedInShell>;
}
