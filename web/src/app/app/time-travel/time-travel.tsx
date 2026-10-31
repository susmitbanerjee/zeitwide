"use client";

import { useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";

import { unwrap, type DayAnswer } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { DayStatus } from "@/components/day-status";
import { Button } from "@/components/ui/button";
import {
  Card,
  ErrorText,
  Input,
  Label,
  SectionTitle,
  Select,
  Status,
} from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { formatDate, formatInstant } from "@/lib/format";
import { errorMessage, useCalendars } from "@/lib/queries";

interface Question {
  calendar: string;
  date: string;
  /** An ISO instant in UTC. */
  asOf: string;
}

/**
 * A5: what Zeitwide answered at a past moment next to what it answers now, with the changes
 * published in between. The API replays the past from its bitemporal history.
 */
export function TimeTravel({ initial }: { initial: Partial<Question> }) {
  const calendars = useCalendars();
  const id = useId();
  const [question, setQuestion] = useState<Question | null>(null);
  return (
    <div className="flex flex-col gap-6">
      <PageHeading
        title="Time travel"
        lead="See any date as Zeitwide recorded it at a past moment, and what changed since."
      />
      <Card className="p-5">
        <form
          className="grid gap-4 sm:grid-cols-[1fr_1fr_1fr_auto] sm:items-end"
          onSubmit={(event) => {
            event.preventDefault();
            const data = new FormData(event.currentTarget);
            setQuestion({
              calendar: String(data.get("calendar")),
              date: String(data.get("date")),
              // datetime-local has no zone; the field is labelled UTC, so the Z is the truth.
              asOf: `${String(data.get("asOf"))}:00Z`,
            });
          }}
        >
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-calendar`}>Calendar</Label>
            <Select
              id={`${id}-calendar`}
              name="calendar"
              defaultValue={initial.calendar ?? "GB-CHAPS"}
              key={calendars.data?.dataset_release}
            >
              {(calendars.data?.calendars ?? []).map((c) => (
                <option key={c.id} value={c.id}>
                  {c.name}
                </option>
              ))}
            </Select>
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-date`}>Date</Label>
            <Input
              id={`${id}-date`}
              name="date"
              type="date"
              required
              defaultValue={initial.date ?? ""}
            />
          </div>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-as-of`}>As of (UTC)</Label>
            <Input id={`${id}-as-of`} name="asOf" type="datetime-local" required />
          </div>
          <Button type="submit" disabled={calendars.data === undefined}>
            Replay
          </Button>
        </form>
      </Card>
      {question !== null && <Replay question={question} />}
    </div>
  );
}

function useAnswer(question: Question, asOf: string | undefined) {
  return useQuery({
    queryKey: ["day", question.calendar, question.date, asOf ?? "now"],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/calendars/{id}/days/{date}", {
          params: {
            path: { id: question.calendar, date: question.date },
            query: asOf === undefined ? {} : { as_of: asOf },
          },
        }),
      ),
  });
}

function Replay({ question }: { question: Question }) {
  const then = useAnswer(question, question.asOf);
  const now = useAnswer(question, undefined);
  const changes = useQuery({
    queryKey: ["changes", question.calendar, question.asOf],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/changes", {
          params: { query: { since: question.asOf, calendars: [question.calendar], limit: 100 } },
        }),
      ),
  });
  const error = then.error ?? now.error;
  if (error !== null) {
    return <ErrorText>{errorMessage(error)}</ErrorText>;
  }
  if (then.data === undefined || now.data === undefined) {
    return <Status>Replaying…</Status>;
  }
  const changed = then.data.is_business_day !== now.data.is_business_day;
  const forDate = changes.data?.changes.filter((c) => c.entry_date === question.date) ?? [];
  return (
    <section aria-labelledby="replay" aria-live="polite" className="flex flex-col gap-4">
      <SectionTitle id="replay">
        {formatDate(question.date, "long")} on {question.calendar}
      </SectionTitle>
      <p className={changed ? "font-semibold text-closed" : "text-ink-muted"}>
        {changed
          ? "The answer has changed since then."
          : "The answer is the same now as it was then."}
      </p>
      <div className="grid gap-4 md:grid-cols-2">
        <AnswerCard title={`As of ${formatInstant(then.data.as_of)}`} answer={then.data} />
        <AnswerCard title="Now" answer={now.data} />
      </div>
      <Card className="flex flex-col gap-2 p-5">
        <h3 className="font-semibold">Changes to this date since then</h3>
        {forDate.length === 0 ? (
          <p className="text-sm text-ink-muted">None.</p>
        ) : (
          <ul className="text-sm">
            {forDate.map((change) => (
              <li key={change.id}>
                Published {formatInstant(change.published_at)}: {change.type.replace("_", " ")} with{" "}
                {change.notice_days} days&apos; notice
              </li>
            ))}
          </ul>
        )}
      </Card>
    </section>
  );
}

function AnswerCard({ title, answer }: { title: string; answer: DayAnswer }) {
  const day = answer.calendars[0];
  return (
    <Card className="flex flex-col gap-2 p-5">
      <h3 className="text-sm font-semibold text-ink-muted">{title}</h3>
      <p className="text-3xl font-bold">{answer.is_business_day ? "Open" : "Closed"}</p>
      {day !== undefined && (
        <DayStatus
          kind={day.is_open ? "open" : day.decided_by === "entry" ? "closed" : "weekend"}
          label={day.reason ?? (day.is_open ? "Business day" : "Weekend")}
        />
      )}
      <p className="font-mono text-xs text-ink-muted">
        release {answer.dataset_release} · receipt {answer.receipt.id}
      </p>
    </Card>
  );
}
