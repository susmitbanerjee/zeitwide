import Link from "next/link";

import type { Calendar, RangeDay } from "@/api/client";
import { dayKind } from "@/lib/answers";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/format";
import { answerPath } from "@/lib/seo";

import { DayLegend } from "./day-status";

/** The days around a date, as the API listed them, each linking to its own answer. */
export function DayStrip({
  calendar,
  days,
  selected,
}: {
  calendar: Calendar;
  days: RangeDay[];
  selected: string;
}) {
  return (
    <div className="flex flex-col gap-3">
      <ol className="grid grid-cols-7 gap-1 sm:grid-cols-[repeat(21,minmax(0,1fr))]">
        {days.map((day) => {
          const kind = dayKind(day, calendar.weekend);
          const status =
            kind === "open"
              ? "open"
              : kind === "closed"
                ? `closed, ${day.reason ?? ""}`
                : "weekend";
          return (
            <li key={day.date}>
              <Link
                href={answerPath(calendar.id, day.date)}
                aria-current={day.date === selected ? "date" : undefined}
                className={cn(
                  "flex h-14 flex-col items-center justify-center rounded text-ink no-underline",
                  kind === "open" && "border border-line bg-surface hover:border-ink",
                  kind === "closed" && "bg-closed text-white hover:text-white",
                  kind === "weekend" && "hatch border border-line text-ink-muted",
                  kind === "weekend-holiday" && "hatch border-2 border-closed",
                  day.date === selected && "outline-2 outline-offset-2 outline-ink",
                )}
              >
                <span className="text-[11px] leading-3">{formatDate(day.date, "weekday")} </span>
                <span className="font-mono text-[15px]">{formatDate(day.date, "day")}</span>
                <span className="sr-only">
                  , {formatDate(day.date, "long")}: {status}
                </span>
              </Link>
            </li>
          );
        })}
      </ol>
      <DayLegend />
    </div>
  );
}
