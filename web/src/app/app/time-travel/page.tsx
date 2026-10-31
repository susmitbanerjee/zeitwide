import type { Metadata } from "next";

import { isIsoDate } from "@/lib/format";

import { TimeTravel } from "./time-travel";

export const metadata: Metadata = { title: "Time travel" };

export default async function TimeTravelPage({ searchParams }: PageProps<"/app/time-travel">) {
  const params = await searchParams;
  const calendar = typeof params.calendar === "string" ? params.calendar : undefined;
  const date = typeof params.date === "string" && isIsoDate(params.date) ? params.date : undefined;
  return (
    <TimeTravel
      initial={{
        ...(calendar === undefined ? {} : { calendar }),
        ...(date === undefined ? {} : { date }),
      }}
    />
  );
}
