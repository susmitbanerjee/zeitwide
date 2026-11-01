"use client";

import { useMutation, useQuery } from "@tanstack/react-query";
import { useId, useState } from "react";

import { unwrap, type DayAnswer, type Receipt } from "@/api/client";
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
  Textarea,
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
        title="Audit"
        lead="Prove what Zeitwide said, when it said it, and whether it still holds."
      />
      <ReceiptChecker />
      <SectionTitle>Replay an answer as of any moment</SectionTitle>
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
      <p className={changed ? "font-semibold text-closed-ink" : "text-ink-muted"}>
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

/** What a pasted receipt is: JSON with an id, a payload and a signature. The API checks the rest. */
export function parseReceipt(text: string): Receipt | null {
  try {
    const value: unknown = JSON.parse(text);
    return typeof value === "object" && value !== null && !Array.isArray(value)
      ? (value as Receipt)
      : null;
  } catch {
    return null;
  }
}

/** Pastes a full receipt and asks the API whether its signature holds. */
function ReceiptChecker() {
  const id = useId();
  const [text, setText] = useState("");
  const [unreadable, setUnreadable] = useState(false);
  const verify = useMutation({
    mutationFn: async (receipt: Receipt) =>
      unwrap(await browserApi.POST("/v1/receipts/verify", { body: receipt })),
  });
  const receipt = verify.variables;
  return (
    <section aria-labelledby={`${id}-title`} className="grid items-start gap-5 lg:grid-cols-2">
      <Card className="p-5">
        <form
          aria-labelledby={`${id}-title`}
          className="flex flex-col gap-4"
          onSubmit={(event) => {
            event.preventDefault();
            const parsed = parseReceipt(text);
            setUnreadable(parsed === null);
            if (parsed !== null) {
              verify.mutate(parsed);
            }
          }}
        >
          <SectionTitle id={`${id}-title`}>Check a receipt</SectionTitle>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-receipt`}>The full receipt, as JSON</Label>
            <Textarea
              id={`${id}-receipt`}
              rows={6}
              className="font-mono text-[13px]"
              value={text}
              onChange={(event) => setText(event.target.value)}
              placeholder='{"id": "rcpt_…", "payload": { … }, "signature": "…"}'
            />
          </div>
          <div>
            <Button type="submit" disabled={text.trim() === "" || verify.isPending}>
              Check
            </Button>
          </div>
          <p className="text-sm text-ink-muted">
            Signatures can also be checked offline with the open library and our published keys. You
            don&apos;t have to trust this page.
          </p>
        </form>
      </Card>
      <div aria-live="polite" className="flex flex-col gap-3">
        {unreadable && (
          <ErrorText>That isn&apos;t a JSON receipt. Paste the whole object.</ErrorText>
        )}
        {verify.isError && <ErrorText>{errorMessage(verify.error)}</ErrorText>}
        {verify.isSuccess && receipt !== undefined && (
          <Card className="flex flex-col gap-3 p-5">
            <p
              className={
                verify.data.valid
                  ? "text-xl font-semibold"
                  : "text-xl font-semibold text-closed-ink"
              }
            >
              {verify.data.valid ? "Signature valid" : "Signature does not match"}
            </p>
            <dl className="grid grid-cols-[max-content_minmax(0,1fr)] gap-x-4 gap-y-1 text-sm">
              <dt className="text-ink-muted">Receipt</dt>
              <dd className="font-mono break-all">{receipt.id}</dd>
              <dt className="text-ink-muted">Issued</dt>
              <dd>{formatInstant(receipt.payload?.issued_at)}</dd>
              <dt className="text-ink-muted">Data used</dt>
              <dd className="font-mono">release {receipt.payload?.dataset_release}</dd>
              <dt className="text-ink-muted">Signed by</dt>
              <dd>
                Ed25519 · key <span className="font-mono">{receipt.key_id}</span>
              </dd>
            </dl>
          </Card>
        )}
      </div>
    </section>
  );
}
