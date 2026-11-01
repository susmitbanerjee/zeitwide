"use client";

import Link from "next/link";
import { useState } from "react";

import { Tip } from "@/components/ui/tooltip";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/format";
import type { CheckData } from "@/lib/landing";
import { answerPath } from "@/lib/seo";

const choice =
  "inline-flex min-h-11 items-center rounded-md border px-3.5 text-[14.5px] font-semibold";

/**
 * The hero card: pick a rail and a date, read the answer. Every answer was fetched from the API on
 * the server; this only looks one up.
 */
export function CheckADate({ data }: { data: CheckData }) {
  const [railId, setRailId] = useState(data.rails[0]?.id ?? "");
  const [date, setDate] = useState(data.today);
  const rail = data.rails.find((entry) => entry.id === railId) ?? data.rails[0];
  const answer = rail?.answers[date];
  if (rail === undefined || answer === undefined) {
    return null;
  }
  const closed = !answer.open;
  const detail = closed
    ? `${answer.reason ?? "Weekend, no settlement"}.${
        answer.next === null ? "" : ` Next business day: ${formatDate(answer.next, "short")}.`
      }`
    : "Open for settlement.";
  const sources =
    answer.publishers.length > 0 ? `Sources: ${answer.publishers.join(" and ")}` : "Weekend rule";
  return (
    <div className="flex flex-col gap-4 rounded-lg border border-line bg-surface p-6">
      <h2 className="text-lg leading-[26px] font-semibold">Check a date</h2>
      <div className="flex flex-col gap-1.5">
        <span id="check-rail" className="text-[13px] font-semibold text-ink-muted">
          Payment rail
        </span>
        <div role="group" aria-labelledby="check-rail" className="flex flex-wrap gap-1.5">
          {data.rails.map((entry) => (
            <Tip key={entry.id} label={`Check ${entry.name}`}>
              <button
                type="button"
                aria-pressed={entry.id === rail.id}
                onClick={() => setRailId(entry.id)}
                className={cn(
                  choice,
                  entry.id === rail.id
                    ? "border-ink bg-ink text-white"
                    : "border-line-strong bg-surface text-ink hover:border-ink hover:bg-surface-sunken",
                )}
              >
                {entry.name}
              </button>
            </Tip>
          ))}
        </div>
      </div>
      <div className="flex flex-col gap-1.5">
        <span id="check-date" className="text-[13px] font-semibold text-ink-muted">
          Date
        </span>
        <div role="group" aria-labelledby="check-date" className="flex flex-wrap gap-1.5">
          {data.dates.map((iso) => (
            <Tip key={iso} label={`Check ${formatDate(iso, "long")}`}>
              <button
                type="button"
                aria-pressed={iso === date}
                onClick={() => setDate(iso)}
                className={cn(
                  choice,
                  "font-normal",
                  iso === date
                    ? "border-ink bg-ink text-white"
                    : "border-line-strong bg-surface text-ink hover:border-ink hover:bg-surface-sunken",
                )}
              >
                {iso === data.today ? "Today" : formatDate(iso, "short")}
              </button>
            </Tip>
          ))}
        </div>
      </div>
      <div
        aria-live="polite"
        className={cn(
          "flex flex-col gap-1.5 rounded-md px-[18px] py-4",
          closed ? "bg-closed-soft" : "bg-canvas",
        )}
      >
        <span className="text-sm leading-5 text-ink-muted">
          Is {formatDate(date, "long")} a {rail.name} business day?
        </span>
        <span
          className={cn(
            "flex items-center gap-2.5 text-[28px] leading-[34px] font-bold tracking-[-0.02em]",
            closed ? "text-closed-ink" : "text-ink",
          )}
        >
          <span
            aria-hidden="true"
            className={cn(
              "size-3 rounded-[2px]",
              closed ? "bg-closed" : "border-[1.5px] border-ink bg-surface",
            )}
          />
          {closed ? "No, closed." : "Yes, open."}
        </span>
        <span className="text-[15px] leading-[23px]">{detail}</span>
        <span className="text-[13px] leading-[19px] text-ink-subtle">
          {sources} · release {data.release}
        </span>
      </div>
      <Link
        href={answerPath(rail.id, date)}
        className="inline-flex min-h-11 items-center self-start text-[15px] font-semibold"
      >
        See the full answer and its evidence
      </Link>
    </div>
  );
}
