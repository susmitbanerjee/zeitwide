"use client";

import { useMutation } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";

import { unwrap, type Shifted } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { CalendarPicker } from "@/components/calendar-picker";
import { ReceiptView } from "@/components/receipt-view";
import { Button } from "@/components/ui/button";
import { Card, ErrorText, Input, Label, SectionTitle, Select } from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { formatDate } from "@/lib/format";
import { errorMessage, useCalendars } from "@/lib/queries";

type Combine = "all_open" | "any_open";

/**
 * A4: moves a number of business days across the chosen calendars. The API does the counting;
 * this page only sends the question and shows the answer and every day it skipped.
 */
export function Calculator() {
  const calendars = useCalendars();
  const all = calendars.data?.calendars ?? [];
  const [selected, setSelected] = useState<string[]>(["US-FEDWIRE", "GB-CHAPS"]);
  const [date, setDate] = useState("");
  const [days, setDays] = useState("2");
  const [combine, setCombine] = useState<Combine>("all_open");
  const id = useId();
  const add = useMutation({
    mutationFn: async (): Promise<Shifted> =>
      unwrap(
        await browserApi.POST("/v1/business-days/add", {
          body: { calendars: selected, date, days: Number(days), combine },
        }),
      ),
  });
  const names = new Map(all.map((c) => [c.id, c.name]));

  return (
    <div className="flex flex-col gap-6">
      <PageHeading
        title="When will it land?"
        lead="Counts only the days when the calendars you need are open, and shows every day it skipped and why."
      />
      <Card className="p-5">
        <form
          className="flex flex-col gap-5"
          onSubmit={(event) => {
            event.preventDefault();
            add.mutate();
          }}
        >
          <CalendarPicker
            legend="Calendars that must be open"
            calendars={all}
            selected={selected}
            onChange={setSelected}
          />
          <div className="grid gap-4 sm:grid-cols-3">
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-date`}>You instruct on</Label>
              <Input
                id={`${id}-date`}
                type="date"
                required
                value={date}
                onChange={(e) => setDate(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-days`}>Business days to add</Label>
              <Input
                id={`${id}-days`}
                type="number"
                inputMode="numeric"
                min={-1000}
                max={1000}
                required
                value={days}
                onChange={(e) => setDays(e.target.value)}
              />
            </div>
            <div className="flex flex-col gap-1">
              <Label htmlFor={`${id}-combine`}>Counts as a business day when</Label>
              <Select
                id={`${id}-combine`}
                value={combine}
                onChange={(e) => setCombine(e.target.value as Combine)}
              >
                <option value="all_open">every calendar is open</option>
                <option value="any_open">any calendar is open</option>
              </Select>
            </div>
          </div>
          <div>
            <Button type="submit" disabled={selected.length === 0 || add.isPending}>
              Calculate
            </Button>
          </div>
        </form>
      </Card>

      {add.isError && <ErrorText>{errorMessage(add.error)}</ErrorText>}
      {add.data !== undefined && (
        <section
          aria-labelledby="result"
          aria-live="polite"
          className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_360px]"
        >
          <Card className="flex flex-col gap-4 p-5">
            <div>
              <p className="text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase">
                Lands on
              </p>
              <h2 id="result" className="font-mono text-3xl font-semibold">
                {formatDate(add.data.date, "long")}
              </h2>
              <p className="text-ink-muted">
                {add.data.days} business days from {formatDate(add.data.start, "long")}.
              </p>
            </div>
            <SectionTitle>Days skipped</SectionTitle>
            {add.data.skipped.length === 0 ? (
              <p className="text-ink-muted">None: every day counted.</p>
            ) : (
              <ul className="flex flex-col divide-y divide-line">
                {add.data.skipped.map((skipped) => (
                  <li key={skipped.date} className="py-2">
                    <span className="font-mono">{formatDate(skipped.date)}</span>
                    <ul className="text-sm text-ink-muted">
                      {skipped.calendars
                        .filter((day) => !day.is_open)
                        .map((day) => (
                          <li key={day.calendar_id}>
                            {names.get(day.calendar_id) ?? day.calendar_id}:{" "}
                            {day.reason ?? "weekend"}
                            {day.decided_by === "entry" && (
                              <>
                                {" · "}
                                <Link href={`/app/evidence/${day.calendar_id}/${skipped.date}`}>
                                  evidence
                                </Link>
                              </>
                            )}
                          </li>
                        ))}
                    </ul>
                  </li>
                ))}
              </ul>
            )}
          </Card>
          <Card className="flex flex-col gap-3 p-5">
            <SectionTitle>Keep a record</SectionTitle>
            <p className="text-sm text-ink-muted">
              Attach the receipt to the payment. It proves which calendars gave this date.
            </p>
            <ReceiptView receipt={add.data.receipt} />
          </Card>
        </section>
      )}
    </div>
  );
}
