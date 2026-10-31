"use client";

import { useQuery } from "@tanstack/react-query";

import { unwrap } from "@/api/client";

import { browserApi } from "./api-browser";

/** Shared reads for the app pages. */

export function useCalendars() {
  return useQuery({
    queryKey: ["calendars"],
    queryFn: async () => unwrap(await browserApi.GET("/v1/calendars")),
    staleTime: 5 * 60_000,
  });
}

export function useDays(calendarId: string, from?: string, to?: string) {
  return useQuery({
    queryKey: ["days", calendarId, from, to],
    queryFn: async () =>
      unwrap(
        await browserApi.GET("/v1/calendars/{id}/days", {
          params: {
            path: { id: calendarId },
            query: { ...(from ? { from } : {}), ...(to ? { to } : {}) },
          },
        }),
      ),
    enabled: calendarId !== "",
  });
}

/** Problem text for an error from a query or mutation. */
export function errorMessage(error: unknown): string {
  return error instanceof Error ? error.message : "Something went wrong";
}
