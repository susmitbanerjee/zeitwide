"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";

import { unwrap, type CandidateStatus } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { Card, ErrorText, Label, Select, Status, Table, Td, Th } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { formatDate, formatInstant } from "@/lib/format";
import { errorMessage } from "@/lib/queries";

/** The review queue: urgent changes (under 14 days' notice) first, as the API orders them. */
export function ReviewQueue() {
  const [status, setStatus] = useState<CandidateStatus>("pending");
  const id = useId();
  const queue = useQuery({
    queryKey: ["candidates", status],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/admin/candidates", { params: { query: { status, limit: 200 } } }),
      ),
  });
  return (
    <div className="flex flex-col gap-6">
      <PageHeading
        title="Review queue"
        lead="Nothing is published without a person's decision. Approved changes are published by the pipeline's next run."
      />
      <div className="flex max-w-xs flex-col gap-1">
        <Label htmlFor={`${id}-status`}>Show</Label>
        <Select
          id={`${id}-status`}
          value={status}
          onChange={(e) => setStatus(e.target.value as CandidateStatus)}
        >
          <option value="pending">Waiting for review</option>
          <option value="approved">Approved, not yet published</option>
          <option value="rejected">Rejected</option>
          <option value="published">Published</option>
        </Select>
      </div>
      {queue.isPending && <Status>Loading the queue…</Status>}
      {queue.isError && <ErrorText>{errorMessage(queue.error)}</ErrorText>}
      {queue.data !== undefined && (
        <Card>
          <Table>
            <caption className="sr-only">Candidate changes</caption>
            <thead>
              <tr>
                <Th scope="col">Change</Th>
                <Th scope="col">Proposed</Th>
                <Th scope="col">Notice</Th>
                <Th scope="col">Sources</Th>
                <Th scope="col">Opened</Th>
              </tr>
            </thead>
            <tbody>
              {queue.data.candidates.length === 0 && (
                <tr>
                  <Td colSpan={5} className="text-ink-muted">
                    Nothing here.
                  </Td>
                </tr>
              )}
              {queue.data.candidates.map((candidate) => (
                <tr key={candidate.id}>
                  <Td>
                    <Link href={`/admin/review/${candidate.id}`} className="font-semibold">
                      {candidate.calendar_id} · {formatDate(candidate.entry_date)}
                    </Link>
                  </Td>
                  <Td>
                    {candidate.after.is_open ? "Open" : "Closed"}: {candidate.after.reason}
                  </Td>
                  <Td>
                    {candidate.urgent ? (
                      <span className="rounded bg-closed-soft px-2 py-0.5 font-semibold text-closed-ink">
                        Urgent · {candidate.notice_days} days
                      </span>
                    ) : (
                      `${candidate.notice_days} days`
                    )}
                  </Td>
                  <Td>
                    {candidate.corroboration === "corroborated" ? (
                      `${candidate.source_count} agree`
                    ) : (
                      <span className="font-semibold text-warning">Single source</span>
                    )}
                  </Td>
                  <Td>{formatInstant(candidate.created_at)}</Td>
                </tr>
              ))}
            </tbody>
          </Table>
        </Card>
      )}
    </div>
  );
}
