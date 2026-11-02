import Link from "next/link";

import { cn } from "@/lib/cn";
import type { AnswerPageModel } from "@/lib/page-models";

import { DayLegend } from "../day-status";

/**
 * The Zeitband for one calendar: 28 days as an HTML table, so crawlers and screen readers read
 * every day. Open is white, closed is orange, weekends hatch; each day links to its own answer.
 */
export function ZeitbandTable({ band }: { band: AnswerPageModel["band"] }) {
  if (band.days.length === 0) {
    return null;
  }
  return (
    <div className="flex flex-col gap-3">
      <div className="overflow-x-auto">
        <table className="w-full border-separate border-spacing-x-0.5 text-center">
          <caption className="pb-2 text-left text-sm font-semibold">{band.caption}</caption>
          <thead>
            <tr>
              <th scope="col" className="sr-only">
                Calendar
              </th>
              {band.days.map((day) => (
                <th
                  key={day.date}
                  scope="col"
                  className={cn(
                    "min-w-7 pb-1 font-mono text-[11.5px] leading-4 font-normal",
                    day.selected ? "font-bold text-ink" : "text-ink-subtle",
                  )}
                >
                  <span aria-hidden="true" className="flex flex-col items-center">
                    <span>{day.initial}</span>
                    <span className="text-[12.5px]">{day.dayNumber}</span>
                  </span>
                  <span className="sr-only">{day.label}</span>
                </th>
              ))}
            </tr>
          </thead>
          <tbody>
            <tr>
              <th scope="row" className="sr-only">
                {band.calendarName}
              </th>
              {band.days.map((day) => (
                <td key={day.date} className="p-0">
                  <Link
                    href={day.href}
                    prefetch={false}
                    aria-current={day.selected ? "date" : undefined}
                    className={cn(
                      "block h-11 min-w-7 rounded-[3px]",
                      day.kind === "open" && "border border-line bg-surface hover:border-ink",
                      day.kind === "closed" && "bg-closed",
                      day.kind === "weekend" && "hatch border border-line",
                      day.kind === "weekend-holiday" && "hatch border-2 border-closed",
                      day.selected && "outline-2 outline-offset-2 outline-ink",
                    )}
                  >
                    <span className="sr-only">{day.fact}</span>
                  </Link>
                </td>
              ))}
            </tr>
          </tbody>
        </table>
      </div>
      <DayLegend />
    </div>
  );
}
