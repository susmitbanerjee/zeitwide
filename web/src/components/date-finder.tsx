"use client";

import { useRouter } from "next/navigation";
import { useId, useState } from "react";

import type { Calendar } from "@/api/client";

import { Button } from "./ui/button";
import { Input, Label, Select } from "./ui/primitives";

/**
 * Goes to the answer page for a calendar and a date the visitor types. It only builds a URL; the
 * answer page asks the API.
 */
export function DateFinder({
  calendars,
  initialDate,
}: {
  calendars: Pick<Calendar, "id" | "name">[];
  initialDate: string;
}) {
  const router = useRouter();
  const id = useId();
  const [calendar, setCalendar] = useState(calendars[0]?.id ?? "");
  const [date, setDate] = useState(initialDate);
  return (
    <form
      className="flex flex-wrap items-end gap-3"
      onSubmit={(event) => {
        event.preventDefault();
        if (calendar !== "" && date !== "") {
          router.push(`/calendars/${encodeURIComponent(calendar)}/${date}`);
        }
      }}
    >
      <div className="flex min-w-48 flex-1 flex-col gap-1">
        <Label htmlFor={`${id}-calendar`}>Calendar</Label>
        <Select
          id={`${id}-calendar`}
          value={calendar}
          onChange={(event) => setCalendar(event.target.value)}
        >
          {calendars.map((option) => (
            <option key={option.id} value={option.id}>
              {option.name}
            </option>
          ))}
        </Select>
      </div>
      <div className="flex min-w-48 flex-1 flex-col gap-1">
        <Label htmlFor={`${id}-date`}>Date</Label>
        <Input
          id={`${id}-date`}
          type="date"
          required
          value={date}
          onChange={(event) => setDate(event.target.value)}
        />
      </div>
      <Button type="submit">Check the date</Button>
    </form>
  );
}
