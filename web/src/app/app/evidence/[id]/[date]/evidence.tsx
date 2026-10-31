"use client";

import { useQuery } from "@tanstack/react-query";
import Link from "next/link";

import { unwrap } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { CopyButton } from "@/components/copy-button";
import { DayStatus } from "@/components/day-status";
import { ReceiptView } from "@/components/receipt-view";
import { Card, ErrorText, SectionTitle, Status } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { formatDate, formatInstant, shortHash } from "@/lib/format";
import { errorMessage, useCalendars } from "@/lib/queries";
import { answerPath } from "@/lib/seo";

/** Every change since records began, so a date's whole history shows. */
const BEGINNING = "1970-01-01T00:00:00Z";

/** A3: how Zeitwide knows the answer for one calendar on one date. */
export function Evidence({ calendarId, date }: { calendarId: string; date: string }) {
  const calendars = useCalendars();
  const calendar = calendars.data?.calendars.find((c) => c.id === calendarId);
  const answer = useQuery({
    queryKey: ["day", calendarId, date],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/calendars/{id}/days/{date}", {
          params: { path: { id: calendarId, date } },
        }),
      ),
  });
  const history = useQuery({
    queryKey: ["changes", calendarId],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/changes", {
          params: { query: { since: BEGINNING, calendars: [calendarId], limit: 100 } },
        }),
      ),
  });
  const name = calendar?.name ?? calendarId;
  const day = answer.data?.calendars[0];
  const changes = history.data?.changes.filter((change) => change.entry_date === date) ?? [];

  return (
    <div className="flex flex-col gap-8">
      <PageHeading
        title={`How we know: ${name} on ${formatDate(date, "long")}`}
        lead="The sources behind the answer, what decided it, and a signed receipt you can check."
      />
      {answer.isPending && <Status>Loading the evidence…</Status>}
      {answer.isError && <ErrorText>{errorMessage(answer.error)}</ErrorText>}
      {answer.data !== undefined && day !== undefined && (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_360px]">
          <div className="flex flex-col gap-6">
            <Card className="flex flex-col gap-3 p-5">
              <div className="flex flex-wrap items-center gap-3">
                <DayStatus
                  kind={day.is_open ? "open" : day.decided_by === "entry" ? "closed" : "weekend"}
                />
                <span className="font-mono text-sm text-ink-muted">
                  {calendarId} · rev {answer.data.calendar_revisions[calendarId]} · release{" "}
                  {answer.data.dataset_release}
                </span>
              </div>
              <p className="text-lg">
                {day.decided_by === "entry"
                  ? `${day.reason ?? "An entry"} (${day.basis ?? "published"}), recorded ${formatInstant(day.recorded_from)}.`
                  : `No entry for this date, so the weekend rule decided: ${day.is_open ? "a business day" : "a weekend day"}.`}
              </p>
              <Link href={answerPath(calendarId, date)}>Back to the answer</Link>
            </Card>

            <section aria-labelledby="sources" className="flex flex-col gap-3">
              <SectionTitle id="sources">Sources, as we read them</SectionTitle>
              {day.sources.length === 0 && (
                <p className="text-ink-muted">
                  The weekend rule is part of the calendar&apos;s published rules.
                </p>
              )}
              <ol className="grid gap-4 md:grid-cols-2">
                {day.sources.map((source, index) => (
                  <li key={source.snapshot_sha256}>
                    <Card className="flex h-full flex-col gap-2 p-5 text-sm">
                      <p className="font-semibold">
                        {String.fromCharCode(65 + index)} · {source.publisher}
                      </p>
                      <a
                        href={source.url}
                        rel="noopener noreferrer"
                        target="_blank"
                        className="break-all"
                      >
                        {source.url}
                      </a>
                      <p className="text-ink-muted">Read {formatInstant(source.retrieved_at)}</p>
                      <p className="text-ink-muted">
                        Snapshot SHA-256{" "}
                        <span className="font-mono text-ink" title={source.snapshot_sha256}>
                          {shortHash(source.snapshot_sha256)}
                        </span>
                      </p>
                    </Card>
                  </li>
                ))}
              </ol>
            </section>

            <section aria-labelledby="history" className="flex flex-col gap-3">
              <SectionTitle id="history">History of this date</SectionTitle>
              {history.isError && <ErrorText>{errorMessage(history.error)}</ErrorText>}
              {history.isSuccess && changes.length === 0 && (
                <p className="text-ink-muted">
                  This date has never changed since we first recorded it.
                </p>
              )}
              <ol className="flex flex-col gap-2">
                {changes.map((change) => (
                  <li key={change.id} className="text-sm">
                    <span className="font-mono">{formatInstant(change.published_at)}</span> ·{" "}
                    {change.type.replace("_", " ")} · {change.notice_days} days&apos; notice
                  </li>
                ))}
              </ol>
              <Link href={`/app/time-travel?calendar=${calendarId}&date=${date}`}>
                See this date as it stood in the past
              </Link>
            </section>
          </div>

          <aside className="flex flex-col gap-4">
            <Card className="flex flex-col gap-3 p-5">
              <SectionTitle>Cite this answer</SectionTitle>
              <p className="text-sm">
                Zeitwide. {calendarId}, {date}: {day.is_open ? "open" : "closed"}
                {day.reason === undefined ? "" : `, ${day.reason}`}. Release{" "}
                {answer.data.dataset_release}, receipt {answer.data.receipt.id}.
              </p>
              <CopyButton
                label="Copy citation"
                text={`Zeitwide. ${calendarId}, ${date}: ${day.is_open ? "open" : "closed"}${day.reason === undefined ? "" : `, ${day.reason}`}. Release ${answer.data.dataset_release}, receipt ${answer.data.receipt.id}.`}
              />
            </Card>
            <Card className="flex flex-col gap-3 p-5">
              <SectionTitle>Signed receipt</SectionTitle>
              <ReceiptView receipt={answer.data.receipt} />
            </Card>
          </aside>
        </div>
      )}
    </div>
  );
}
