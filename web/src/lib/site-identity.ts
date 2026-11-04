/**
 * Who publishes Zeitwide and under what terms, as the footer shows it and the JSON-LD states it.
 * Each value here is on every public page, so structured data may repeat it.
 */
export const ORGANIZATION = {
  name: "Zeitwide",
  logoPath: "/logo.png",
  profiles: [
    { text: "Source code on GitHub", href: "https://github.com/susmitbanerjee/zeitwide" },
    { text: "Python library on PyPI", href: "https://pypi.org/project/zeitwide/" },
  ],
} as const;

export const DATASET_LICENSE = {
  name: "CC BY-NC 4.0",
  url: "https://creativecommons.org/licenses/by-nc/4.0/",
} as const;

/** Stable JSON-LD node IDs. Other sites may link to them, so they never change. */
export const NODE_IDS = {
  organization: (siteUrl: string) => new URL("/#org", siteUrl).toString(),
  website: (siteUrl: string) => new URL("/#website", siteUrl).toString(),
  api: (siteUrl: string) => new URL("/#api", siteUrl).toString(),
  dataset: (siteUrl: string, calendarId: string) =>
    new URL(`/calendars/${encodeURIComponent(calendarId)}#dataset`, siteUrl).toString(),
} as const;
