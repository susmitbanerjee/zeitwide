import type { Metadata } from "next";
import Link from "next/link";
import type { ReactNode } from "react";

import { CheckADate } from "@/components/landing/check-a-date";
import { Zeitband } from "@/components/landing/zeitband";
import { buttonVariants } from "@/components/ui/button";
import { Code, Eyebrow } from "@/components/ui/primitives";
import { groupCalendars } from "@/lib/answers";
import { getCalendars, getDays } from "@/lib/api-server";
import { cn } from "@/lib/cn";
import { env } from "@/lib/env";
import { formatDate } from "@/lib/format";
import { bandRails, checkData, pickRails, upcomingClosures, type RailRange } from "@/lib/landing";
import { answerPath } from "@/lib/seo";

export const metadata: Metadata = { alternates: { canonical: env.siteUrl } };

const h2 = "text-[40px] leading-[46px] font-bold tracking-[-0.025em] text-balance";
const lead = "text-lg leading-7 text-ink-muted";
const textLink = "inline-flex min-h-11 items-center self-start text-[15px] font-semibold";
const mono = "font-mono";

const INCIDENTS = [
  {
    when: "September 2022 · United Kingdom",
    figure: "9",
    unit: "days’ notice",
    text: "A bank holiday was added for the Queen’s state funeral on 19 September. The Bank of England closed CHAPS for the day.",
  },
  {
    when: "December 2018 · United States",
    figure: "4",
    unit: "days’ notice",
    text: "A national day of mourning for President Bush on 5 December. Bond markets closed; ISDA still treated it as a New York banking day.",
  },
  {
    when: "June 2021 · United States",
    figure: "1",
    unit: "day’s notice",
    text: "Juneteenth became a federal holiday and was observed the next day. The Fed kept its payment services open, so “federal holiday” didn’t mean “rail closed”.",
  },
] as const;

const STEPS = [
  {
    title: "We read the official sources every day",
    text: "The Federal Reserve, the ECB, the Bank of England and GOV.UK. Each page is saved and fingerprinted, so we can show exactly what it said.",
  },
  {
    title: "Two sources agree, then a person checks",
    text: "Nothing is published on one source alone. Dates the operator hasn’t published yet are marked “projected”, never passed off as fact.",
  },
  {
    title: "You get the answer with a signed receipt",
    text: "Keep it with the payment. Months later, an auditor can check what we said and why, without taking anyone’s word for it.",
  },
] as const;

const FAQ = [
  {
    q: "How is this different from a public-holiday list?",
    a: "A public-holiday list says Whit Monday is a German holiday. It won’t tell you T2 stays open that day, or that FedNow never closes. We answer per payment rail.",
  },
  {
    q: "What happens if you get a date wrong?",
    a: "We publish the correction, send a webhook to everyone using that calendar, and log it. Your receipt shows what we said at the time.",
  },
  {
    q: "Can I use it without the internet?",
    a: "Yes. The Python library and the local MCP server carry the full dataset. They give the same answers as the API for the same release, without receipts.",
  },
  {
    q: "Who runs Zeitwide?",
    a: "An independent team. We aren’t affiliated with the Federal Reserve, the ECB or the Bank of England; we read what they publish and link to it.",
  },
] as const;

export default async function HomePage() {
  const { calendars, dataset_release: release } = await getCalendars();
  const railCalendars = pickRails(
    await Promise.all(
      calendars.map(async (calendar) => ({
        calendar,
        range: await getDays(calendar.id, calendar.revision),
      })),
    ).then((entries) => entries.filter((entry): entry is RailRange => entry.range !== null)),
  );
  const check = checkData(railCalendars, release);
  const band = bandRails(railCalendars);
  const next = upcomingClosures(railCalendars);
  const first = railCalendars[0];
  const nextClosure = next[0];

  return (
    <div className="flex flex-col gap-24 pt-4 pb-6">
      <section aria-labelledby="m-title" className="flex flex-col gap-10">
        <div className="flex flex-wrap items-end gap-x-16 gap-y-8">
          <div className="flex min-w-0 flex-[999_1_35rem] flex-col gap-[22px]">
            <h1
              id="m-title"
              className="max-w-[11em] text-5xl leading-[1.05] font-bold tracking-[-0.035em] text-balance sm:text-7xl sm:leading-[76px]"
            >
              Business days you can prove.
            </h1>
            <p className="max-w-[38em] text-xl leading-[31px] text-pretty text-ink-muted">
              Zeitwide tells you when Fedwire, FedACH, FedNow, T2 and CHAPS are open, cites the
              official source for every closed day, and warns you when a date changes. For people,
              for code, and for AI agents.
            </p>
            <div className="flex flex-wrap gap-2.5">
              <Link href="/calendars" className={cn(buttonVariants(), "min-h-12 px-5 text-base")}>
                See the calendars
              </Link>
              <Link
                href="/app/developers"
                prefetch={false}
                className={cn(buttonVariants({ variant: "outline" }), "min-h-12 px-5 text-base")}
              >
                Get a free API key
              </Link>
            </div>
          </div>
          {check !== null && (
            <div className="min-w-0 max-w-[35rem] flex-[1_1_27.5rem]">
              <CheckADate data={check} />
            </div>
          )}
        </div>
        {first !== undefined && <Zeitband rails={band} today={first.range.today} />}
      </section>

      <section aria-labelledby="m-why" className="flex flex-col gap-7">
        <div className="flex max-w-3xl flex-col gap-2.5">
          <h2 id="m-why" className={h2}>
            Calendars change, often at short notice.
          </h2>
          <p className={lead}>
            A holiday list in a spreadsheet is right until the day it isn’t. These three were real.
          </p>
        </div>
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(20rem,100%),1fr))] border-t-2 border-ink">
          {INCIDENTS.map((incident, index) => (
            <article
              key={incident.when}
              className={cn(
                "flex flex-col gap-2.5 pt-[22px] pb-2",
                index === 0 ? "pr-7" : "border-line pl-7 lg:border-l",
              )}
            >
              <span className="font-mono text-[13px] text-ink-subtle">{incident.when}</span>
              <span className="flex items-baseline gap-2">
                <span className={cn(mono, "text-5xl leading-[52px]")}>{incident.figure}</span>
                <span className="text-base font-semibold">{incident.unit}</span>
              </span>
              <p className="text-[15.5px] leading-6 text-ink-muted">{incident.text}</p>
            </article>
          ))}
        </div>
      </section>

      <section aria-labelledby="m-how" className="flex flex-wrap gap-x-16 gap-y-8">
        <div className="flex min-w-0 flex-[1_1_22rem] flex-col gap-3.5">
          <h2 id="m-how" className={h2}>
            Every answer shows its work.
          </h2>
          <p className={lead}>
            We don’t guess and we don’t copy other lists. Each closed day traces back to the
            operator that decided it.
          </p>
          {first !== undefined && nextClosure !== undefined && (
            <Link href={answerPath(first.calendar.id, nextClosure.date)} className={textLink}>
              See the evidence for one date
            </Link>
          )}
        </div>
        <ol className="m-0 flex min-w-0 flex-[999_1_40rem] list-none flex-col border-t-2 border-ink p-0">
          {STEPS.map((step, index) => (
            <li
              key={step.title}
              className="grid grid-cols-[3.5rem_minmax(0,1fr)] gap-4 border-b border-line py-[22px]"
            >
              <span className="font-mono text-[28px] leading-8 text-ink-subtle">
                {String(index + 1).padStart(2, "0")}
              </span>
              <div className="flex flex-col gap-1">
                <span className="text-xl leading-[26px] font-semibold">{step.title}</span>
                <span className="text-[15.5px] leading-6 text-ink-muted">{step.text}</span>
              </div>
            </li>
          ))}
        </ol>
      </section>

      <section aria-labelledby="m-who" className="flex flex-col gap-7">
        <h2 id="m-who" className={h2}>
          One set of dates, three ways in.
        </h2>
        <div className="grid grid-cols-[repeat(auto-fit,minmax(min(22rem,100%),1fr))] gap-4">
          <AudienceCard
            eyebrow="Payment operations"
            title="Know what’s open today on every rail."
            href="/app"
            action="See the Today view"
            body="Alerts by email or webhook when a closure is announced, with the dates and sources attached."
          >
            <dl className="flex flex-col border-t border-line text-[14.5px] leading-[21px]">
              {railCalendars.map(({ calendar, range }) => {
                const today = range.days.find((day) => day.date === range.today);
                return (
                  <div
                    key={calendar.id}
                    className="flex justify-between gap-3 border-b border-line py-2.5"
                  >
                    <dt>{calendar.name}</dt>
                    <dd className="font-mono">
                      {today === undefined
                        ? "–"
                        : today.is_open
                          ? "Open"
                          : today.decided_by === "weekend_rule"
                            ? "Weekend"
                            : "Closed"}
                    </dd>
                  </div>
                );
              })}
            </dl>
          </AudienceCard>
          <AudienceCard
            eyebrow="Treasury and payroll"
            title="Plan payments so they land on time."
            href="/app/calculator"
            action="Try the planner"
            body="Closures straight into Outlook, Google Calendar or Apple Calendar, so the whole team sees them."
          >
            {first !== undefined && nextClosure !== undefined && (
              <div className="flex flex-col gap-1 rounded-md bg-canvas px-4 py-3.5 text-[14.5px] leading-[21px]">
                <span className="text-ink-muted">
                  Next closure on {nextClosure.closed[0]?.name ?? first.calendar.name}
                </span>
                <span className="text-xl leading-7 font-bold">
                  {formatDate(nextClosure.date, "short")}
                </span>
                <span className="text-ink-muted">{nextClosure.closed[0]?.reason}</span>
              </div>
            )}
          </AudienceCard>
          <AudienceCard
            dark
            eyebrow="Engineers and AI agents"
            title="Stop hard-coding holiday lists."
            href="/docs"
            action="Read the quickstart"
            body="REST API, Python library, MCP server for agents, and signed webhooks when a date changes."
          >
            {nextClosure?.closed[0] !== undefined && (
              <Code className="overflow-x-hidden bg-[#22262c] text-[13px] break-all whitespace-pre-wrap">
                {`GET /v1/calendars/${nextClosure.closed[0].id}/days/${nextClosure.date}

"open": false
"reason": "${nextClosure.closed[0].reason}"
"release": "${release}"`}
              </Code>
            )}
          </AudienceCard>
        </div>
      </section>

      {next.length > 0 && (
        <section aria-labelledby="m-next" className="flex flex-wrap gap-x-16 gap-y-8">
          <div className="flex min-w-0 flex-[1_1_22rem] flex-col gap-3.5">
            <h2 id="m-next" className={h2}>
              What’s changing next.
            </h2>
            <p className={lead}>Live from the same dataset that answers every request.</p>
            <Link href="/app/changes" prefetch={false} className={textLink}>
              See all changes
            </Link>
          </div>
          <ul className="m-0 flex min-w-0 flex-[999_1_40rem] list-none flex-col border-t-2 border-ink p-0">
            {next.map((closure) => (
              <li
                key={closure.date}
                className="grid gap-x-5 gap-y-1 border-b border-line py-[18px] sm:grid-cols-[9rem_minmax(0,1fr)]"
              >
                <span className="font-mono text-sm leading-6">
                  {formatDate(closure.date, "medium")}
                </span>
                <div className="flex flex-col gap-0.5">
                  <span className="text-[17px] leading-6 font-semibold">
                    {closure.closed.map((rail) => rail.name).join(", ")} closed:{" "}
                    {closure.closed[0]?.reason}
                  </span>
                  {closure.stillOpen.length > 0 && (
                    <span className="text-[15px] leading-[23px] text-ink-muted">
                      {closure.stillOpen.join(", ")}{" "}
                      {closure.stillOpen.length === 1 ? "stays" : "stay"} open.
                    </span>
                  )}
                </div>
              </li>
            ))}
          </ul>
        </section>
      )}

      <section aria-labelledby="m-cover" className="flex flex-wrap gap-4">
        <div className="flex min-w-0 flex-[999_1_40rem] flex-col gap-[18px] rounded-lg border border-line bg-surface p-7">
          <h2 id="m-cover" className="text-[28px] leading-9 font-bold tracking-[-0.015em]">
            What we cover today
          </h2>
          <div className="grid grid-cols-[repeat(auto-fit,minmax(min(15rem,100%),1fr))] gap-x-8 text-[15px] leading-[22px]">
            {groupCalendars(calendars).map((group) => (
              <div key={group.label} className="flex flex-col">
                <span className="pb-2 text-[13px] font-semibold text-ink-subtle">
                  {group.label}
                </span>
                {group.calendars.map((calendar) => (
                  <span
                    key={calendar.id}
                    className="flex justify-between gap-3 border-t border-line py-[9px]"
                  >
                    <Link
                      href={`/calendars/${calendar.id}`}
                      className="text-ink no-underline hover:underline"
                    >
                      {calendar.name}
                    </Link>
                    <span className="font-mono text-[13px] text-ink-muted">{calendar.id}</span>
                  </span>
                ))}
              </div>
            ))}
          </div>
        </div>
        <div className="flex min-w-0 flex-[1_1_24rem] flex-col gap-4 rounded-lg bg-ink p-7 text-white">
          <Eyebrow className="text-[#a9afb8]">Pricing</Eyebrow>
          <span className="text-[40px] leading-[44px] font-bold tracking-[-0.025em]">
            Free in the beta.
          </span>
          <ul className="m-0 flex list-none flex-col border-t border-[#343941] p-0 text-[15px] leading-[22px] text-[#e9ebee]">
            {[
              "Commercial use of the API included",
              "10,000 calls a day per key",
              "Receipts, webhooks and the MCP server",
              "60 days’ notice by email before any paid plan",
            ].map((line) => (
              <li key={line} className="border-b border-[#343941] py-2.5 last:border-b-0">
                {line}
              </li>
            ))}
          </ul>
          <Link
            href="/app/developers"
            prefetch={false}
            className="mt-auto inline-flex min-h-12 items-center self-start rounded-md bg-white px-5 text-base font-semibold text-ink no-underline hover:bg-canvas hover:text-ink"
          >
            Get a free API key
          </Link>
        </div>
      </section>

      <section aria-labelledby="m-faq" className="flex flex-wrap gap-x-16 gap-y-8">
        <h2 id="m-faq" className={cn(h2, "flex-[1_1_22rem]")}>
          Questions
        </h2>
        <div className="flex min-w-0 flex-[999_1_40rem] flex-col border-t-2 border-ink">
          {FAQ.map((item, index) => (
            <details key={item.q} open={index === 0} className="border-b border-line py-4">
              <summary className="min-h-7 cursor-pointer text-[17px] font-semibold">
                {item.q}
              </summary>
              <p className="mt-2.5 text-[15.5px] leading-6 text-ink-muted">{item.a}</p>
            </details>
          ))}
        </div>
      </section>

      <section
        aria-labelledby="m-cta"
        className="flex flex-wrap items-center justify-between gap-6 rounded-lg border border-line bg-surface p-10"
      >
        <h2 id="m-cta" className="text-[32px] leading-10 font-bold tracking-[-0.02em]">
          Know before the calendar changes.
        </h2>
        <div className="flex flex-wrap gap-2.5">
          <Link
            href="/app/changes"
            prefetch={false}
            className={cn(buttonVariants({ variant: "outline" }), "min-h-12 px-5 text-base")}
          >
            Get closure alerts
          </Link>
          <Link
            href="/app/developers"
            prefetch={false}
            className={cn(buttonVariants(), "min-h-12 px-5 text-base")}
          >
            Get a free API key
          </Link>
        </div>
      </section>
      <p className="text-sm text-ink-muted">
        Dataset release <span className="font-mono">{release}</span>.
      </p>
    </div>
  );
}

function AudienceCard({
  eyebrow,
  title,
  body,
  href,
  action,
  dark = false,
  children,
}: {
  eyebrow: string;
  title: string;
  body: string;
  href: string;
  action: string;
  dark?: boolean;
  children: ReactNode;
}) {
  return (
    <article
      className={cn(
        "flex flex-col gap-3.5 rounded-lg p-6",
        dark ? "bg-ink text-white" : "border border-line bg-surface",
      )}
    >
      <Eyebrow className={cn("text-[13px] leading-[18px]", dark && "text-[#a9afb8]")}>
        {eyebrow}
      </Eyebrow>
      <span className="text-[22px] leading-[30px] font-semibold">{title}</span>
      {children}
      <p className={cn("text-[15px] leading-[23px]", dark ? "text-line-strong" : "text-ink-muted")}>
        {body}
      </p>
      <Link
        href={href}
        prefetch={false}
        className={cn(
          "mt-auto inline-flex min-h-11 items-center text-[15px] font-semibold",
          dark && "text-white hover:text-white",
        )}
      >
        {action}
      </Link>
    </article>
  );
}
