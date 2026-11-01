import type { Metadata, Viewport } from "next";
import { Fragment_Mono, Schibsted_Grotesk } from "next/font/google";

import "./globals.css";

const grotesk = Schibsted_Grotesk({
  variable: "--font-grotesk",
  subsets: ["latin"],
  // A variable font: every weight, one file.
  display: "swap",
});

const fragmentMono = Fragment_Mono({
  variable: "--font-fragment",
  subsets: ["latin"],
  weight: "400",
  display: "swap",
});

const siteUrl = process.env.SITE_URL ?? "http://localhost:3000";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: { default: "Zeitwide: business days you can prove", template: "%s · Zeitwide" },
  description:
    "Is this a business day on Fedwire, FedACH, FedNow, T2 or CHAPS, and what is the next one? Every answer backed by official sources.",
  applicationName: "Zeitwide",
  openGraph: { siteName: "Zeitwide", type: "website" },
};

export const viewport: Viewport = { themeColor: "#111317", colorScheme: "light" };

export default function RootLayout({ children }: LayoutProps<"/">) {
  return (
    <html
      lang="en-GB"
      className={`${grotesk.variable} ${fragmentMono.variable} h-full antialiased`}
    >
      <body className="flex min-h-full flex-col">{children}</body>
    </html>
  );
}
