import type { Metadata } from "next";

import { AuthScreen } from "@/components/auth-screen";

export const metadata: Metadata = { title: "Sign in", robots: { index: false, follow: false } };

// Rendered per request so its scripts carry the CSP nonce (see the app layout).
export const instant = false;

export default function SignInPage({ searchParams }: PageProps<"/signin">) {
  return <AuthScreen mode="signin" searchParams={searchParams} />;
}
