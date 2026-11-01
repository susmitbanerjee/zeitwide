import { dayKind } from "@/lib/answers";
import { cn } from "@/lib/cn";
import { formatDate } from "@/lib/format";
import { bandSummary, type BandRail } from "@/lib/landing";

import { DaySwatch } from "../day-status";
import { Tip } from "../ui/tooltip";

/** The Zeitband: the next three weeks, one row per rail. Open is white, closed is orange, weekends hatch. */
export function Zeitband({
  rails,
  today,
  title,
}: {
  rails: BandRail[];
  today: string;
  /** The caption before the date range; defaults to the next three weeks. */
  title?: string;
}) {
  const header = rails[0]?.days ?? [];
  if (header.length === 0) {
    return null;
  }
  const first = formatDate(header[0]!.date, "plain");
  const last = formatDate(header.at(-1)!.date, "plain");
  const summary = bandSummary(
    rails,
    (date) => formatDate(date, "short"),
    (day, calendar) => dayKind(day, calendar.weekend) === "weekend-holiday",
  );
  const dense = header.length > 35;
  const columns = {
    gridTemplateColumns: `minmax(5.5rem,7rem) repeat(${header.length}, minmax(0, 1fr))`,
  };
  return (
    <figure className="m-0 flex flex-col gap-2.5 rounded-lg border border-line bg-surface px-6 pt-5 pb-4">
      <figcaption className="flex flex-wrap justify-between gap-x-6 gap-y-2 text-sm leading-5">
        <span className="font-semibold">
          {title ?? "The next three weeks"} on{" "}
          {rails.length === 1 ? "one rail" : `${rails.length} rails`}, {first} to {last}
        </span>
        <ul className="flex flex-wrap gap-x-4 gap-y-1.5 text-ink-muted">
          <li className="inline-flex items-center gap-1.5">
            <DaySwatch kind="open" className="size-3" />
            Open
          </li>
          <li className="inline-flex items-center gap-1.5">
            <DaySwatch kind="closed" className="size-3" />
            Closed
          </li>
          <li className="inline-flex items-center gap-1.5">
            <DaySwatch kind="weekend" className="size-3" />
            Weekend
          </li>
        </ul>
      </figcaption>
      <div role="img" aria-label={summary} className="overflow-x-auto">
        <div className="min-w-[44rem]">
          <div
            aria-hidden="true"
            className="grid font-mono text-[11.5px] leading-[15px]"
            style={columns}
          >
            <span />
            {header.map((day) => {
              const weekday = formatDate(day.date, "weekday");
              // A long band can't fit every number: it names Mondays and month starts.
              const named = !dense || weekday === "Mon" || formatDate(day.date, "day") === "1";
              return (
                <span
                  key={day.date}
                  className={cn(
                    "flex flex-col items-center py-1",
                    day.date === today ? "font-bold text-ink" : "text-ink-subtle",
                  )}
                >
                  <span>{dense ? "" : weekday.slice(0, 1)}</span>
                  <span className="text-[12.5px]">{named ? formatDate(day.date, "day") : ""}</span>
                </span>
              );
            })}
          </div>
          {rails.map(({ calendar, days }) => (
            <div key={calendar.id} aria-hidden="true" className="grid" style={columns}>
              <span className="flex items-center text-sm">{calendar.name}</span>
              {days.map((day) => {
                const kind = dayKind(day, calendar.weekend);
                return (
                  <Tip
                    key={day.date}
                    label={`${calendar.name}, ${formatDate(day.date, "long")}: ${kind === "open" ? "open" : (day.reason ?? "weekend")}`}
                  >
                    <span
                      className={cn(
                        "mr-0.5 mb-1 h-[30px] rounded-[3px]",
                        kind === "open" && "border border-line bg-surface",
                        kind === "closed" && "bg-closed",
                        kind === "weekend" && "hatch border border-line",
                        kind === "weekend-holiday" && "hatch border-2 border-closed",
                      )}
                    />
                  </Tip>
                );
              })}
            </div>
          ))}
        </div>
      </div>
    </figure>
  );
}
