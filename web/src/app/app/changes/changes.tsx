"use client";

import { useMutation, useQuery, useQueryClient } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";

import { unwrap, type AlertRule, type ChangeType } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { CalendarPicker } from "@/components/calendar-picker";
import { Button } from "@/components/ui/button";
import { Card, ErrorText, Input, Label, SectionTitle, Status } from "@/components/ui/primitives";
import { browserApi, idempotencyKey } from "@/lib/api-browser";
import { formatDate, formatInstant } from "@/lib/format";
import { errorMessage, useCalendars } from "@/lib/queries";
import { answerPath } from "@/lib/seo";

const BEGINNING = "1970-01-01T00:00:00Z";
const TYPES: { value: ChangeType; label: string }[] = [
  { value: "closure_added", label: "New closures" },
  { value: "closure_removed", label: "Closures removed" },
  { value: "closure_changed", label: "Closures changed" },
  { value: "basis_changed", label: "Projected became published" },
];

/** A6: published changes, newest first, and the alert rules that decide what reaches you. */
export function Changes() {
  const calendars = useCalendars();
  const names = new Map((calendars.data?.calendars ?? []).map((c) => [c.id, c.name]));
  const rules = useQuery({
    queryKey: ["alert-rules"],
    queryFn: async () => unwrap(await browserApi.GET("/v1/account/alert-rules")),
  });
  const [onlyMine, setOnlyMine] = useState(false);
  const watched = [...new Set((rules.data ?? []).flatMap((rule) => rule.calendars))];
  const feed = useQuery({
    queryKey: ["changes", "all"],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/changes", {
          params: { query: { since: BEGINNING, limit: 100 } },
        }),
      ),
  });
  // The API lists oldest first; the newest matter most here.
  const changes = [...(feed.data?.changes ?? [])]
    .reverse()
    .filter((change) => !onlyMine || watched.includes(change.calendar_id));

  return (
    <div className="flex flex-col gap-6">
      <PageHeading
        title="Changes"
        lead="Everything that changed in the calendars we publish, newest first, and the rules that decide which ones alert you."
      />
      <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_380px]">
        <section aria-labelledby="feed" className="flex flex-col gap-3">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <SectionTitle id="feed">Published changes</SectionTitle>
            <label className="inline-flex min-h-11 items-center gap-2 text-sm">
              <input
                type="checkbox"
                className="size-4 accent-[#1f4fd8]"
                checked={onlyMine}
                onChange={(event) => setOnlyMine(event.target.checked)}
              />
              Only calendars in my alert rules
            </label>
          </div>
          {feed.isPending && <Status>Loading changes…</Status>}
          {feed.isError && <ErrorText>{errorMessage(feed.error)}</ErrorText>}
          {feed.isSuccess && changes.length === 0 && (
            <p className="text-ink-muted">No changes to show.</p>
          )}
          <ol className="flex flex-col gap-3">
            {changes.map((change) => (
              <li key={change.id}>
                <Card className="flex flex-col gap-2 p-5">
                  <p className="flex flex-wrap gap-2 text-xs font-semibold tracking-[0.06em] text-ink-subtle uppercase">
                    <span>{TYPES.find((t) => t.value === change.type)?.label ?? change.type}</span>
                    <span>· Published {formatInstant(change.published_at)}</span>
                    {change.notice_days < 14 && (
                      <span className="rounded bg-closed-soft px-1.5 text-closed">
                        Short notice · {change.notice_days} days
                      </span>
                    )}
                  </p>
                  <h3 className="text-lg font-semibold">
                    {names.get(change.calendar_id) ?? change.calendar_id} on{" "}
                    {formatDate(change.entry_date, "long")}
                  </h3>
                  <p className="text-sm">
                    <span className="text-ink-muted">Before: </span>
                    {describe(change.before)} <span className="text-ink-muted">· After: </span>
                    {describe(change.after)}
                  </p>
                  <div className="flex flex-wrap gap-4 text-sm">
                    <Link href={answerPath(change.calendar_id, change.entry_date)}>
                      Answer page
                    </Link>
                    <Link href={`/app/evidence/${change.calendar_id}/${change.entry_date}`}>
                      Evidence
                    </Link>
                  </div>
                </Card>
              </li>
            ))}
          </ol>
        </section>
        <AlertRules
          rules={rules.data ?? []}
          calendars={calendars.data?.calendars ?? []}
          loading={rules.isPending}
        />
      </div>
    </div>
  );
}

function describe(state: Record<string, unknown> | null | undefined): string {
  if (state === null || state === undefined) {
    return "nothing recorded";
  }
  const open = state.is_open === true ? "open" : "closed";
  return typeof state.reason === "string" ? `${open}, ${state.reason}` : open;
}

function AlertRules({
  rules,
  calendars,
  loading,
}: {
  rules: AlertRule[];
  calendars: { id: string; name: string }[];
  loading: boolean;
}) {
  const client = useQueryClient();
  const id = useId();
  const [editing, setEditing] = useState<AlertRule | null>(null);
  const [name, setName] = useState("");
  const [chosen, setChosen] = useState<string[]>([]);
  const [events, setEvents] = useState<ChangeType[]>([
    "closure_added",
    "closure_removed",
    "closure_changed",
  ]);
  const [notice, setNotice] = useState("");
  const reset = () => {
    setEditing(null);
    setName("");
    setChosen([]);
    setNotice("");
  };
  const save = useMutation({
    mutationFn: async () => {
      const body = {
        name,
        calendars: chosen,
        events,
        max_notice_days: notice === "" ? null : Number(notice),
      };
      return editing === null
        ? unwrap(
            await browserApi.POST("/v1/account/alert-rules", {
              body,
              params: { header: { "Idempotency-Key": idempotencyKey() } },
            }),
          )
        : unwrap(
            await browserApi.PUT("/v1/account/alert-rules/{id}", {
              body,
              params: { path: { id: editing.id } },
            }),
          );
    },
    onSuccess: async () => {
      reset();
      await client.invalidateQueries({ queryKey: ["alert-rules"] });
    },
  });
  const remove = useMutation({
    mutationFn: async (ruleId: string) => {
      await browserApi.DELETE("/v1/account/alert-rules/{id}", { params: { path: { id: ruleId } } });
    },
    onSuccess: () => client.invalidateQueries({ queryKey: ["alert-rules"] }),
  });

  return (
    <aside aria-labelledby="rules" className="flex flex-col gap-4">
      <SectionTitle id="rules">Alert rules</SectionTitle>
      <p className="text-sm text-ink-muted">
        While you have rules, your <Link href="/app/developers">webhooks</Link> receive only the
        changes a rule matches.
      </p>
      {loading && <Status>Loading rules…</Status>}
      <ul className="flex flex-col gap-2">
        {rules.map((rule) => (
          <li key={rule.id}>
            <Card className="flex flex-col gap-1 p-4 text-sm">
              <p className="font-semibold">{rule.name}</p>
              <p className="text-ink-muted">
                {rule.calendars.join(", ")} ·{" "}
                {rule.max_notice_days === null
                  ? "any notice"
                  : `${rule.max_notice_days} days' notice or less`}
              </p>
              <div className="flex gap-2">
                <Button
                  size="sm"
                  variant="ghost"
                  aria-label={`Edit ${rule.name}`}
                  onClick={() => {
                    setEditing(rule);
                    setName(rule.name);
                    setChosen(rule.calendars);
                    setEvents(rule.events);
                    setNotice(rule.max_notice_days === null ? "" : String(rule.max_notice_days));
                  }}
                >
                  Edit
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  aria-label={`Delete ${rule.name}`}
                  onClick={() => remove.mutate(rule.id)}
                >
                  Delete
                </Button>
              </div>
            </Card>
          </li>
        ))}
      </ul>
      <Card className="p-4">
        <form
          className="flex flex-col gap-3"
          aria-label={editing === null ? "New alert rule" : `Edit ${editing.name}`}
          onSubmit={(event) => {
            event.preventDefault();
            save.mutate();
          }}
        >
          <h3 className="font-semibold">
            {editing === null ? "New alert rule" : `Edit ${editing.name}`}
          </h3>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-name`}>Name</Label>
            <Input
              id={`${id}-name`}
              required
              maxLength={100}
              value={name}
              onChange={(e) => setName(e.target.value)}
            />
          </div>
          <CalendarPicker calendars={calendars} selected={chosen} onChange={setChosen} max={20} />
          <fieldset className="flex flex-col">
            <legend className="mb-1 text-sm font-semibold">Changes</legend>
            {TYPES.map((type) => (
              <label key={type.value} className="inline-flex min-h-11 items-center gap-2 text-sm">
                <input
                  type="checkbox"
                  className="size-4 accent-[#1f4fd8]"
                  checked={events.includes(type.value)}
                  onChange={(e) =>
                    setEvents(
                      e.target.checked
                        ? [...events, type.value]
                        : events.filter((v) => v !== type.value),
                    )
                  }
                />
                {type.label}
              </label>
            ))}
          </fieldset>
          <div className="flex flex-col gap-1">
            <Label htmlFor={`${id}-notice`}>
              Only with this many days&apos; notice or less (optional)
            </Label>
            <Input
              id={`${id}-notice`}
              type="number"
              inputMode="numeric"
              min={0}
              max={3650}
              value={notice}
              onChange={(e) => setNotice(e.target.value)}
            />
          </div>
          {save.isError && <ErrorText>{errorMessage(save.error)}</ErrorText>}
          <div className="flex gap-2">
            <Button
              type="submit"
              disabled={chosen.length === 0 || events.length === 0 || save.isPending}
            >
              {editing === null ? "Add rule" : "Save rule"}
            </Button>
            {editing !== null && (
              <Button type="button" variant="outline" onClick={reset}>
                Cancel
              </Button>
            )}
          </div>
        </form>
      </Card>
    </aside>
  );
}
