"use client";

import type { Calendar } from "@/api/client";

/** A fieldset of calendar checkboxes; each label is a 44 px target. */
export function CalendarPicker({
  calendars,
  selected,
  onChange,
  legend = "Calendars",
  max = 10,
}: {
  calendars: Pick<Calendar, "id" | "name">[];
  selected: string[];
  onChange: (next: string[]) => void;
  legend?: string;
  max?: number;
}) {
  return (
    <fieldset className="flex flex-col gap-1">
      <legend className="mb-1 text-sm font-semibold">{legend}</legend>
      <div className="grid grid-cols-[repeat(auto-fill,minmax(min(15rem,100%),1fr))] gap-x-4">
        {calendars.map((calendar) => {
          const checked = selected.includes(calendar.id);
          return (
            <label
              key={calendar.id}
              className="inline-flex min-h-11 items-center gap-2 text-[15px]"
            >
              <input
                type="checkbox"
                className="size-4 accent-[#2a3fcf]"
                checked={checked}
                disabled={!checked && selected.length >= max}
                onChange={(event) =>
                  onChange(
                    event.target.checked
                      ? [...selected, calendar.id]
                      : selected.filter((id) => id !== calendar.id),
                  )
                }
              />
              {calendar.name}
              <span className="font-mono text-xs text-ink-subtle">{calendar.id}</span>
            </label>
          );
        })}
      </div>
    </fieldset>
  );
}
