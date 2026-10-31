"use client";

import { useQuery } from "@tanstack/react-query";

import { unwrap } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { Card, ErrorText, Status, Table, Td, Th } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { formatInstant, shortHash } from "@/lib/format";
import { errorMessage } from "@/lib/queries";

/** Dataset releases, newest first. A release never changes once cut. */
export function Releases() {
  const releases = useQuery({
    queryKey: ["releases"],
    queryFn: async () =>
      unwrap(await browserApi.GET("/v1/admin/releases", { params: { query: { limit: 50 } } })),
  });
  return (
    <div className="flex flex-col gap-6">
      <PageHeading title="Releases" lead="Every answer names the release it came from." />
      {releases.isPending && <Status>Loading releases…</Status>}
      {releases.isError && <ErrorText>{errorMessage(releases.error)}</ErrorText>}
      {releases.data !== undefined && (
        <Card>
          <Table>
            <caption className="sr-only">Dataset releases</caption>
            <thead>
              <tr>
                <Th scope="col">Release</Th>
                <Th scope="col">Captures up to</Th>
                <Th scope="col">Calendar revisions</Th>
                <Th scope="col">Export SHA-256</Th>
              </tr>
            </thead>
            <tbody>
              {releases.data.releases.map((release) => (
                <tr key={release.id}>
                  <Td className="font-mono font-semibold">{release.id}</Td>
                  <Td>{formatInstant(release.as_of)}</Td>
                  <Td className="font-mono text-xs">
                    {Object.entries(release.calendar_revisions)
                      .map(([calendar, revision]) => `${calendar}=${revision}`)
                      .join(", ")}
                  </Td>
                  <Td className="font-mono" title={release.export_sha256}>
                    {shortHash(release.export_sha256)}
                  </Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}
    </div>
  );
}
