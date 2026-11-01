"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";

import { unwrap, type EntryState } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { Button } from "@/components/ui/button";
import { Card, ErrorText, Label, SectionTitle, Status, Textarea } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { formatDate, formatInstant, shortHash } from "@/lib/format";
import { errorMessage } from "@/lib/queries";

/** One candidate: what changes, every snapshot behind it side by side, and the decision. */
export function Review({ candidateId }: { candidateId: number }) {
  const client = useQueryClient();
  const id = useId();
  const [reason, setReason] = useState("");
  const detail = useQuery({
    queryKey: ["candidate", candidateId],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/admin/candidates/{id}", {
          params: { path: { id: candidateId } },
        }),
      ),
  });
  const decide = useMutation({
    mutationFn: async (decision: "approve" | "reject") =>
      unwrap(
        await browserApi.POST("/v1/admin/candidates/{id}/decision", {
          params: { path: { id: candidateId } },
          body: { decision, reason },
        }),
      ),
    onSuccess: async (updated) => {
      client.setQueryData(["candidate", candidateId], updated);
      await client.invalidateQueries({ queryKey: ["candidates"] });
    },
  });

  if (detail.isPending) {
    return <Status>Loading the candidate…</Status>;
  }
  if (detail.isError) {
    return <ErrorText>{errorMessage(detail.error)}</ErrorText>;
  }
  const { candidate, current, snapshots } = detail.data;
  return (
    <div className="flex flex-col gap-6">
      <Link href="/admin/review">Back to the queue</Link>
      <PageHeading
        title={`${candidate.calendar_id} on ${formatDate(candidate.entry_date, "long")}`}
        lead={`${candidate.notice_days} days' notice · ${
          candidate.corroboration === "corroborated"
            ? `${candidate.source_count} sources agree`
            : "single source"
        } · ${candidate.status}`}
      />
      {candidate.urgent && (
        <p className="rounded-md bg-closed-soft px-4 py-3 font-semibold text-closed-ink">
          Urgent: less than 14 days&apos; notice.
        </p>
      )}
      <div className="grid gap-4 md:grid-cols-3">
        <StateCard title="Published now" state={current ?? null} />
        <StateCard title="When proposed" state={candidate.before ?? null} />
        <StateCard title="Proposed" state={candidate.after} emphasis />
      </div>

      <section aria-labelledby="snapshots" className="flex flex-col gap-3">
        <SectionTitle id="snapshots">Snapshots, side by side</SectionTitle>
        <ol className="grid gap-4 md:grid-cols-2">
          {snapshots.map((snapshot) => (
            <li key={`${snapshot.source}-${snapshot.snapshot_sha256}`}>
              <Card className="flex h-full flex-col gap-2 p-5 text-sm">
                <p className="text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase">
                  {snapshot.role}
                </p>
                <p className="text-base font-semibold">{snapshot.publisher}</p>
                <a
                  href={snapshot.url}
                  rel="noopener noreferrer"
                  target="_blank"
                  className="break-all"
                >
                  {snapshot.url}
                </a>
                <dl className="grid grid-cols-[max-content_minmax(0,1fr)] gap-x-3 gap-y-1">
                  <dt className="text-ink-muted">Reads</dt>
                  <dd className="font-semibold">
                    {snapshot.is_open ? "Open" : "Closed"}: {snapshot.reason}
                  </dd>
                  <dt className="text-ink-muted">Fetched</dt>
                  <dd>{formatInstant(snapshot.fetched_at)}</dd>
                  <dt className="text-ink-muted">SHA-256</dt>
                  <dd className="font-mono" title={snapshot.snapshot_sha256}>
                    {shortHash(snapshot.snapshot_sha256)}
                  </dd>
                  <dt className="text-ink-muted">Response</dt>
                  <dd>
                    HTTP {snapshot.http_status} · {snapshot.content_type ?? "unknown type"} ·{" "}
                    {snapshot.byte_size.toLocaleString("en-GB")} bytes
                  </dd>
                  <dt className="text-ink-muted">Parser</dt>
                  <dd className="font-mono">{snapshot.parser_version}</dd>
                </dl>
              </Card>
            </li>
          ))}
        </ol>
      </section>

      <Card className="flex flex-col gap-3 p-5">
        <SectionTitle>Decision</SectionTitle>
        {candidate.status === "pending" ? (
          <form className="flex flex-col gap-3" onSubmit={(event) => event.preventDefault()}>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-reason`}>Reason (recorded with your name)</Label>
              <Textarea
                id={`${id}-reason`}
                required
                maxLength={1000}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
              />
            </div>
            {decide.isError && <ErrorText>{errorMessage(decide.error)}</ErrorText>}
            <div className="flex flex-wrap gap-2">
              <Button
                type="button"
                variant="accent"
                disabled={reason.trim() === "" || decide.isPending}
                onClick={() => decide.mutate("approve")}
              >
                Approve
              </Button>
              <Button
                type="button"
                variant="danger"
                disabled={reason.trim() === "" || decide.isPending}
                onClick={() => decide.mutate("reject")}
              >
                Reject
              </Button>
            </div>
          </form>
        ) : (
          <p role="status">
            <span className="font-semibold capitalize">{candidate.status}</span> by{" "}
            <span className="font-mono">{detail.data.reviewer}</span> on{" "}
            {formatInstant(detail.data.reviewed_at)}: {detail.data.review_reason}
          </p>
        )}
      </Card>
    </div>
  );
}

function StateCard({
  title,
  state,
  emphasis = false,
}: {
  title: string;
  state: EntryState | null;
  emphasis?: boolean;
}) {
  return (
    <Card className={emphasis ? "border-2 border-ink p-4" : "p-4"}>
      <h2 className="text-sm font-semibold text-ink-muted">{title}</h2>
      {state === null ? (
        <p className="mt-1">No entry (the weekend rule decides)</p>
      ) : (
        <p className="mt-1">
          <span className={state.is_open ? "font-semibold" : "font-semibold text-closed-ink"}>
            {state.is_open ? "Open" : "Closed"}
          </span>
          : {state.reason}
          {state.basis === undefined ? "" : ` (${state.basis})`}
        </p>
      )}
    </Card>
  );
}
