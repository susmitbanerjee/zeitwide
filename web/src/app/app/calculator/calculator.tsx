"use client";

import { useMutation } from "@tanstack/react-query";
import Link from "next/link";
import { useId, useState } from "react";

import { unwrap, type Shifted } from "@/api/client";
import { PageHeading } from "@/components/app-chrome";
import { CalendarPicker } from "@/components/calendar-picker";
import { ReceiptView } from "@/components/receipt-view";
import { Button } from "@/components/ui/button";
import {
  Card,
  Code,
  ErrorText,
  Input,
  Label,
  SectionTitle,
  Select,
} from "@/components/ui/primitives";
import { browserApi } from "@/lib/api-browser";
import { formatDate, plural } from "@/lib/format";
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
      <div className="grid items-start gap-5 lg:grid-cols-[minmax(20rem,26rem)_minmax(0,1fr)]">
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
            <div className="grid gap-4">
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

        <div className="flex min-w-0 flex-col gap-5">
          {add.isError && <ErrorText>{errorMessage(add.error)}</ErrorText>}
          {add.data === undefined && !add.isError && (
            <Card className="flex flex-col gap-4 p-6">
              <p className="font-semibold">How the planner works</p>
              <ol className="m-0 flex list-none flex-col gap-3 p-0 text-ink-muted">
                {[
                  "Choose the calendars that must all be open for a day to count.",
                  "Pick the day you instruct and how many business days to add.",
                  "Read the date it lands on, and every day it skipped and why.",
                ].map((step, index) => (
                  <li key={step} className="flex gap-3">
                    <span className="font-mono text-ink-subtle">0{index + 1}</span>
                    {step}
                  </li>
                ))}
              </ol>
            </Card>
          )}
          {add.data !== undefined && (
            <section aria-labelledby="result" aria-live="polite" className="flex flex-col gap-5">
              <Card className="flex flex-col gap-3 p-6">
                <p className="text-xs font-semibold tracking-[0.08em] text-ink-subtle uppercase">
                  Lands on
                </p>
                <div className="flex flex-wrap items-baseline gap-x-5 gap-y-1">
                  <h2 id="result" className="font-mono text-[44px] leading-[52px]">
                    {formatDate(add.data.date, "long")}
                  </h2>
                  <p className="text-[17px] text-ink-muted">
                    {plural(add.data.days, "business day")} from{" "}
                    {formatDate(add.data.start, "long")}
                    {add.data.skipped.length === 0
                      ? ""
                      : ` · ${plural(add.data.skipped.length, "day")} skipped`}
                  </p>
                </div>
              </Card>
              <Card className="overflow-x-auto">
                <table className="w-full min-w-[34rem] border-collapse text-left text-[14.5px]">
                  <caption className="px-5 pt-4 pb-1.5 text-left text-base font-semibold">
                    Days skipped
                  </caption>
                  <thead>
                    <tr className="text-xs tracking-[0.06em] text-ink-subtle uppercase">
                      <th scope="col" className="border-b border-line px-5 py-2.5">
                        Date
                      </th>
                      <th scope="col" className="border-b border-line px-3 py-2.5">
                        Why it didn&apos;t count
                      </th>
                    </tr>
                  </thead>
                  <tbody>
                    {add.data.skipped.length === 0 && (
                      <tr>
                        <td colSpan={2} className="px-5 py-4 text-ink-muted">
                          None: every day counted.
                        </td>
                      </tr>
                    )}
                    {add.data.skipped.map((skipped) => (
                      <tr key={skipped.date}>
                        <td className="border-b border-line px-5 py-3 align-top font-mono text-sm">
                          {formatDate(skipped.date, "short")}
                        </td>
                        <td className="border-b border-line px-3 py-3">
                          <ul className="flex flex-col gap-1">
                            {skipped.calendars
                              .filter((day) => !day.is_open)
                              .map((day) => (
                                <li key={day.calendar_id}>
                                  <span className="font-semibold">
                                    {names.get(day.calendar_id) ?? day.calendar_id}
                                  </span>
                                  : {day.reason ?? "weekend"}
                                  {day.decided_by === "entry" && (
                                    <>
                                      {" · "}
                                      <Link
                                        href={`/app/evidence/${day.calendar_id}/${skipped.date}`}
                                      >
                                        evidence
                                      </Link>
                                    </>
                                  )}
                                </li>
                              ))}
                          </ul>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </Card>
              <div className="flex flex-wrap gap-4">
                <div className="flex min-w-0 flex-[1_1_22rem] flex-col gap-2.5">
                  <SectionTitle>Same plan through the API</SectionTitle>
                  <Code>
                    {`POST /v1/business-days/add\n${JSON.stringify(
                      { calendars: selected, date, days: Number(days), combine },
                      null,
                      2,
                    )}\n\u2192 "date": "${add.data.date}"`}
                  </Code>
                </div>
                <Card className="flex min-w-0 flex-[1_1_16rem] flex-col gap-3 p-5">
                  <SectionTitle>Keep a record</SectionTitle>
                  <p className="text-sm text-ink-muted">
                    Attach the receipt to the payment. It proves which calendars gave this date.
                  </p>
                  <ReceiptView receipt={add.data.receipt} />
                </Card>
              </div>
            </section>
          )}
        </div>
      </div>
    </div>
  );
}
