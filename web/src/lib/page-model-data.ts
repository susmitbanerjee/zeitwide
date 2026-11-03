import "server-only";

import { getCalendars } from "./api-server";
import { env } from "./env";
import {
  answerPageModel,
  calendarPageModel,
  calendarsPageModel,
  comparePageModel,
  pricingPageModel,
  sourcesPageModel,
  yearPageModel,
  type PageModel,
} from "./page-models";
import {
  answerPageData,
  calendarPageData,
  calendarYearSummaries,
  comparePageData,
  sourcesPageData,
  yearPageData,
} from "./public-data";
import { comparePath } from "./public-pages";
import type { PublicRoute } from "./public-routes";

export type LoadedPage = { kind: "page"; model: PageModel } | { kind: "redirect"; path: string };

/**
 * The model for a public data route, a redirect to its one canonical path, or null when none
 * exists. Docs are Markdown files, not models (see lib/docs.ts).
 */
export async function loadPage(
  route: Exclude<PublicRoute, { type: "doc" }>,
): Promise<LoadedPage | null> {
  const site = env.siteUrl;
  const page = (model: PageModel | null): LoadedPage | null =>
    model === null ? null : { kind: "page", model };
  switch (route.type) {
    case "calendars": {
      const [entries, list] = await Promise.all([calendarYearSummaries(), getCalendars()]);
      return page(calendarsPageModel(entries, list.dataset_release, site));
    }
    case "calendar": {
      const data = await calendarPageData(route.id);
      return page(data === null ? null : calendarPageModel(data, site));
    }
    case "year": {
      const data = await yearPageData(route.id, route.year);
      return page(data === null ? null : yearPageModel(data, site));
    }
    case "answer": {
      const data = await answerPageData(route.id, route.date);
      return page(data === null ? null : answerPageModel(data, site));
    }
    case "compare": {
      const data = await comparePageData(route.pair, route.year);
      if (data === null) {
        return null;
      }
      return data.kind === "redirect"
        ? { kind: "redirect", path: comparePath(data.idA, data.idB, route.year) }
        : page(comparePageModel(data, site));
    }
    case "sources": {
      const { sources, release, years } = await sourcesPageData();
      return page(sourcesPageModel(sources, release, years, site));
    }
    case "pricing":
      return page(pricingPageModel(site));
  }
}
