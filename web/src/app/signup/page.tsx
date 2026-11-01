import type { Metadata } from "next";

import { AuthScreen } from "@/components/auth-screen";

export const metadata: Metadata = {
  title: "Create an account",
  robots: { index: false, follow: false },
};

// Rendered per request so its scripts carry the CSP nonce (see the app layout).
export const instant = false;

export default function SignUpPage({ searchParams }: PageProps<"/signup">) {
  return <AuthScreen mode="signup" searchParams={searchParams} />;
}
