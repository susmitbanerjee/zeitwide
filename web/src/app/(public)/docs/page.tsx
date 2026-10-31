import type { Metadata } from "next";
import Link from "next/link";

import { buttonVariants } from "@/components/ui/button";
import { Card, Code, SectionTitle } from "@/components/ui/primitives";
import { env } from "@/lib/env";
import { absoluteUrl } from "@/lib/seo";

export const metadata: Metadata = {
  title: "Developer quickstart",
  description:
    "Stop hard-coding holiday lists: call the Zeitwide API, connect an agent over MCP, or get webhooks when a date changes.",
  alternates: { canonical: absoluteUrl(env.siteUrl, "/docs") },
};

const VERIFY = `import crypto from "node:crypto";

// Zeitwide-Signature: t=<unix seconds>,v1=<hex HMAC-SHA256 of "t.body">
export function verify(header, body, secret) {
  const parts = Object.fromEntries(header.split(",").map((p) => p.split("=")));
  if (Math.abs(Date.now() / 1000 - Number(parts.t)) > 300) return false;
  const expected = crypto.createHmac("sha256", secret).update(\`\${parts.t}.\${body}\`).digest();
  return crypto.timingSafeEqual(expected, Buffer.from(parts.v1, "hex"));
}`;

/** C3: the developer quickstart. */
export default function DocsPage() {
  return (
    <div className="flex flex-col gap-10">
      <header className="flex flex-col gap-3">
        <h1 className="text-4xl font-bold tracking-[-0.02em]">Stop hard-coding holiday lists.</h1>
        <p className="max-w-2xl text-lg text-ink-muted">
          One API for Fedwire, FedACH, FedNow, T2, CHAPS and public holidays. 10,000 calls a day per
          key, free in the beta. Every answer names its sources and carries a signed receipt.
        </p>
        <div className="flex flex-wrap gap-2">
          <Link
            href="/app/developers"
            prefetch={false}
            className={buttonVariants({ variant: "primary" })}
          >
            Get a free API key
          </Link>
          <Link href="/docs/api" className={buttonVariants({ variant: "outline" })}>
            API reference
          </Link>
        </div>
      </header>

      <section aria-labelledby="first-call" className="flex flex-col gap-3">
        <SectionTitle id="first-call">Make your first call</SectionTitle>
        <p className="text-ink-muted">
          Every call needs a key. Create one for free in the app; it takes a minute.
        </p>
        <Code>{`curl https://api.zeitwide.com/v1/calendars/GB-CHAPS/days/2026-12-28 \\
  -H "Authorization: Bearer zw_live_…"

curl -X POST https://api.zeitwide.com/v1/business-days/add \\
  -H "Authorization: Bearer zw_live_…" -H "Content-Type: application/json" \\
  -d '{"calendars":["US-FEDWIRE","GB-CHAPS"],"date":"2026-12-24","days":2}'`}</Code>
      </section>

      <section aria-labelledby="carries" className="flex flex-col gap-3">
        <SectionTitle id="carries">What every answer carries</SectionTitle>
        <div className="grid gap-4 md:grid-cols-3">
          <Card className="p-5">
            <h3 className="font-mono font-semibold">sources</h3>
            <p className="text-sm text-ink-muted">
              The official pages behind each closure, with the hash of the copy we read.
            </p>
          </Card>
          <Card className="p-5">
            <h3 className="font-mono font-semibold">dataset_release</h3>
            <p className="text-sm text-ink-muted">
              The same request against the same release always gives the same answer.
            </p>
          </Card>
          <Card className="p-5">
            <h3 className="font-mono font-semibold">receipt</h3>
            <p className="text-sm text-ink-muted">
              An Ed25519-signed record of the question and answer. Check it with{" "}
              <span className="font-mono">POST /v1/receipts/verify</span> or offline with our public
              keys.
            </p>
          </Card>
        </div>
      </section>

      <section aria-labelledby="connect" className="flex flex-col gap-3">
        <SectionTitle id="connect">Connect an agent</SectionTitle>
        <Code>{`{ "mcpServers": { "zeitwide": { "url": "${env.mcpUrl}" } } }

uvx zeitwide-mcp --offline`}</Code>
        <p className="text-sm text-ink-muted">
          Six tools with the same answers as the API.{" "}
          <Link href="/app/developers/mcp" prefetch={false}>
            MCP setup
          </Link>
        </p>
      </section>

      <section aria-labelledby="webhooks" className="flex flex-col gap-3">
        <SectionTitle id="webhooks">Get told when a date changes</SectionTitle>
        <p className="text-ink-muted">
          Subscribe a webhook to closure changes. Check the signature before you act on it:
        </p>
        <Code>{VERIFY}</Code>
      </section>
    </div>
  );
}
