import type { Metadata } from "next";
import Link from "next/link";

import { PageHeading } from "@/components/app-chrome";
import { CopyButton } from "@/components/copy-button";
import { Card, Code, SectionTitle, Table, Td, Th } from "@/components/ui/primitives";
import { env } from "@/lib/env";

export const metadata: Metadata = { title: "MCP setup" };

const TOOLS = [
  ["is_business_day", "Whether a date is a business day on one or more calendars, and why."],
  ["next_business_day", "The first business day after a date."],
  ["add_business_days", "Move N business days, with every skipped day and its reason."],
  ["list_closures", "Every closure on a calendar between two dates."],
  ["explain_date", "The entry, sources and snapshots behind one date."],
  ["get_changes", "Changes published since a time, to re-check old answers."],
] as const;

/** C2: connecting an agent to the hosted MCP server, or running the open one locally. */
export default function McpPage() {
  const hosted = JSON.stringify(
    {
      mcpServers: {
        zeitwide: {
          url: env.mcpUrl,
          headers: { Authorization: "Bearer YOUR_API_KEY" },
        },
      },
    },
    null,
    2,
  );
  const local = JSON.stringify(
    { mcpServers: { zeitwide: { command: "uvx", args: ["zeitwide-mcp", "--offline"] } } },
    null,
    2,
  );
  return (
    <div className="flex flex-col gap-8">
      <PageHeading
        title="Connect an AI agent"
        lead="Six tools, the same answers as the API, each with its sources and a signed receipt."
      />
      <div className="grid gap-6 lg:grid-cols-2">
        <Card className="flex flex-col gap-3 p-5">
          <SectionTitle>Hosted MCP server</SectionTitle>
          <p className="text-sm text-ink-muted">
            Streamable HTTP. Send an API key as a bearer token: put your key where it says{" "}
            <span className="font-mono">YOUR_API_KEY</span>.{" "}
            <Link href="/app/developers">Create a key</Link>.
          </p>
          <Code>{hosted}</Code>
          <div>
            <CopyButton text={hosted} label="Copy configuration" />
          </div>
        </Card>
        <Card className="flex flex-col gap-3 p-5">
          <SectionTitle>Local, offline</SectionTitle>
          <p className="text-sm text-ink-muted">
            The open library bundles the latest dataset release. No key, no network.
          </p>
          <Code>{local}</Code>
          <div>
            <CopyButton text={local} label="Copy configuration" />
          </div>
        </Card>
      </div>
      <section aria-labelledby="tools" className="flex flex-col gap-3">
        <SectionTitle id="tools">The tools</SectionTitle>
        <Card>
          <Table>
            <thead>
              <tr>
                <Th scope="col">Tool</Th>
                <Th scope="col">What it answers</Th>
              </tr>
            </thead>
            <tbody>
              {TOOLS.map(([name, description]) => (
                <tr key={name}>
                  <Td className="font-mono">{name}</Td>
                  <Td>{description}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
        <p className="text-sm text-ink-muted">
          The server tells agents to cite sources, keep receipts and call{" "}
          <span className="font-mono">get_changes</span> before acting on an old date.
        </p>
      </section>
    </div>
  );
}
