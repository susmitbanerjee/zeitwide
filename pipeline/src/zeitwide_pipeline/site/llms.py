"""llms.txt (https://llmstxt.org) and llms-full.txt, from a release and the docs."""

from collections.abc import Sequence
from dataclasses import dataclass
from urllib.parse import urlsplit

from zeitwide_pipeline.site.docs import Doc
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.paths import (
    answer_path,
    calendar_path,
    compare_pairs,
    compare_path,
    year_path,
)
from zeitwide_pipeline.site.release_view import (
    CalendarView,
    Closure,
    ReleaseView,
    long_date,
    medium_date,
)

MAX_LLMS_TXT_BYTES = 10 * 1024


def plural(count: int, word: str) -> str:
    return f"{count} {word}" if count == 1 else f"{count} {word}s"


def list_of(items: Sequence[str]) -> str:
    if len(items) <= 1:
        return "".join(items)
    return f"{', '.join(items[:-1])} and {items[-1]}"


@dataclass(frozen=True, slots=True)
class Discovery:
    """Counts llms.txt states about the other site files."""

    sitemap_urls: int
    sitemap_files: int
    named_agents: int


def llms_txt(
    release: ReleaseView, docs: Sequence[Doc], links: SiteLinks, discovery: Discovery
) -> str:
    """The llms.txt index: one H1, a summary, how to get Markdown, then sections of links.

    Raises:
        ValueError: when it would be over 10 KB.
    """
    closures = sum(len(calendar.closures) for calendar in release.calendars)
    years = sorted({year for c in release.calendars for year in c.entry_years})
    span = f" from {years[0]} to {years[-1]}" if years else ""
    lines = [
        "# Zeitwide",
        "",
        "> Zeitwide answers whether a date is a business day, and which day is next, for "
        f"{plural(len(release.calendars), 'calendar')}: payment rails (Fedwire, FedACH, FedNow, "
        "T2, CHAPS) and public holiday calendars. Every answer names its official sources. "
        f"Dataset release {release.id} (as of {release.today.isoformat()}) holds "
        f"{plural(closures, 'closure')}{span}.",
        "",
        "Every page is also Markdown: add `.md` to its URL, or send `Accept: text/markdown`.",
        f"API: {links.api_url.rstrip('/')}/v1 (OpenAPI: {links.openapi_url}); "
        f"MCP server: {links.mcp_url}.",
        "",
        "## Answers",
        "",
        *_answers(release, links),
        "",
        "## Calendars",
        "",
        *_calendars(release, links),
        "",
        "## Compare",
        "",
        *_compare(release, links),
        "",
        "## Docs",
        "",
        *[
            f"- [{doc.title}]({links.markdown(doc.path)}): {doc.description} Updated {doc.updated}."
            for doc in docs
        ],
        f"- [All docs in one file]({links.page('/docs/llms-full.txt')}): "
        f"{plural(len(docs), 'page')} plus the API reference, from release {release.id}.",
        "",
        "## Discovery",
        "",
        f"- [Sitemap]({links.page('/sitemap.xml')}): an index of "
        f"{plural(discovery.sitemap_files, 'sitemap')} listing {discovery.sitemap_urls} URLs, "
        "dated by each calendar's last change.",
        f"- [robots.txt]({links.page('/robots.txt')}): allows all crawlers and names "
        f"{plural(discovery.named_agents, 'AI search or user-fetch agent')}.",
        f"- [Calendars index]({links.markdown('/calendars')}): "
        f"{plural(len(release.calendars), 'calendar')} with time zones, weekends and coverage.",
        f"- [Sources]({links.markdown('/sources')}): "
        f"{plural(source_count(release), 'official source URL')} behind release {release.id}.",
        "",
        "## Optional",
        "",
        f"- [Everything in one file]({links.page('/llms-full.txt')}): all "
        f"{plural(closures, 'closure')} as tables, one per calendar, plus the docs and the API "
        "reference.",
        *_next_year(release, links),
        f"- [Pricing]({links.markdown('/pricing')}): free during the beta, with 10,000 API calls "
        "a day per key.",
    ]
    text = "\n".join(lines) + "\n"
    size = len(text.encode())
    if size > MAX_LLMS_TXT_BYTES:
        raise ValueError(f"llms.txt is {size} bytes, over {MAX_LLMS_TXT_BYTES}")
    return text


def _hosts(closure: Closure) -> str:
    return list_of(
        list(dict.fromkeys(urlsplit(url).hostname or url for url in closure.source_urls))
    )


def _answers(release: ReleaseView, links: SiteLinks) -> list[str]:
    """For each calendar, its next closure after the release date (FedNow: today, open)."""
    lines = []
    for calendar in release.calendars:
        if not calendar.closes_on_holidays:
            day = release.today
            lines.append(
                f"- [Is {long_date(day)} a {calendar.name} business day?]"
                f"({links.markdown(answer_path(calendar.id, day))}): Yes; {calendar.name} closes "
                "on no holiday and no weekend, so every day is a business day."
            )
            continue
        upcoming = calendar.closures_after(release.today)
        if upcoming:
            closure = upcoming[0]
            lines.append(
                f"- [Is {long_date(closure.date)} a {calendar.name} business day?]"
                f"({links.markdown(answer_path(calendar.id, closure.date))}): No; "
                f"{calendar.name} is closed for {closure.reason} ({closure.basis}, "
                f"source {_hosts(closure)})."
            )
    return lines


def _calendars(release: ReleaseView, links: SiteLinks) -> list[str]:
    lines = []
    for calendar in release.calendars:
        weekend = (
            "no weekend"
            if not calendar.weekend
            else f"closed every {list_of([day.capitalize() for day in calendar.weekend])}"
        )
        if calendar.closures:
            years = calendar.entry_years
            upcoming = calendar.closures_after(release.today)
            next_one = (
                f"; next {medium_date(upcoming[0].date)} ({upcoming[0].reason})" if upcoming else ""
            )
            span = f"in {years[0]}" if years[0] == years[-1] else f"from {years[0]} to {years[-1]}"
            facts = f"{plural(len(calendar.closures), 'closure')} {span}{next_one}"
        else:
            facts = "open every day of the year"
        lines.append(
            f"- [{calendar.name}]({links.markdown(calendar_path(calendar.id))}): {facts}; "
            f"{weekend}; {calendar.timezone}; revision {calendar.revision}."
        )
    return lines


def _compare(release: ReleaseView, links: SiteLinks) -> list[str]:
    """Rail pairs in the release's year (all pairs are in the sitemap)."""
    year = release.today.year
    lines = []
    for a, b in compare_pairs(release, year):
        if a.kind != "rail" or b.kind != "rail":
            continue
        dates_a = {closure.date for closure in a.closures_in(year)}
        dates_b = {closure.date for closure in b.closures_in(year)}
        lines.append(
            f"- [{a.name} vs {b.name} in {year}]"
            f"({links.markdown(compare_path(a.id, b.id, year))}): {a.name} closes on "
            f"{plural(len(dates_a), 'day')} and {b.name} on {len(dates_b)}; they share "
            f"{len(dates_a & dates_b)}."
        )
    return lines


def _next_year(release: ReleaseView, links: SiteLinks) -> list[str]:
    year = release.today.year + 1
    lines = []
    for calendar in release.calendars:
        if not calendar.closes_on_holidays or year not in calendar.covered_years([year]):
            continue
        closures = calendar.closures_in(year)
        weekdays = sum(1 for closure in closures if not calendar.is_weekend(closure.date))
        lines.append(
            f"- [{calendar.name} closures in {year}]"
            f"({links.markdown(year_path(calendar.id, year))}): "
            f"{plural(len(closures), 'closing day')}, "
            f"{'1 on a weekday' if weekdays == 1 else f'{weekdays} on weekdays'}."
        )
    return lines


def source_count(release: ReleaseView) -> int:
    return len(
        {url for c in release.calendars for closure in c.closures for url in closure.source_urls}
    )


# ---- llms-full.txt ----


def _header(title: str, release: ReleaseView, links: SiteLinks) -> list[str]:
    generated = release.as_of.isoformat().replace("+00:00", "Z")
    return [
        f"# {title}",
        "",
        f"> Release {release.id}. Generated {generated}, the release's as-of time, so this file "
        f"changes only when the data does. Source: {links.page('/')}",
    ]


def _docs_part(docs: Sequence[Doc], api_reference: str, links: SiteLinks) -> list[str]:
    lines: list[str] = []
    for doc in docs:
        lines += [
            "",
            "---",
            "",
            f"Source: {links.page(doc.path)} (updated {doc.updated})",
            "",
            doc.body.strip(),
        ]
    return [*lines, "", "---", "", api_reference.strip()]


def docs_full_txt(
    release: ReleaseView, docs: Sequence[Doc], api_reference: str, links: SiteLinks
) -> str:
    """Every doc page and the API reference, for /docs/llms-full.txt."""
    lines = [*_header("Zeitwide docs", release, links), *_docs_part(docs, api_reference, links)]
    return "\n".join(lines) + "\n"


def llms_full_txt(
    release: ReleaseView, docs: Sequence[Doc], api_reference: str, links: SiteLinks
) -> str:
    """Docs, API reference, and every calendar's closures as one Markdown table each."""
    lines = [
        *_header("Zeitwide: everything in one file", release, links),
        *_docs_part(docs, api_reference, links),
        "",
        "---",
        "",
        "# Closures by calendar",
    ]
    for calendar in release.calendars:
        lines += ["", *closure_table(calendar, links)]
    return "\n".join(lines) + "\n"


CLOSURE_TABLE_HEADER = "| Date | Weekday | Reason | Basis | Source |"


def closure_table(calendar: CalendarView, links: SiteLinks) -> list[str]:
    """A heading, one line of facts, and a row per closure, published and projected."""
    weekend = ", ".join(day.capitalize() for day in calendar.weekend) or "none"
    lines = [
        f"## {calendar.name} ({calendar.id})",
        "",
        f"{calendar.timezone}; weekend {weekend}; revision {calendar.revision}; "
        f"{plural(len(calendar.closures), 'closure')}. "
        f"Page: {links.markdown(calendar_path(calendar.id))}",
        "",
    ]
    if not calendar.closures:
        return [*lines, "No closures: this calendar closes on no holiday."]
    lines += [CLOSURE_TABLE_HEADER, "| --- | --- | --- | --- | --- |"]
    lines += [
        f"| {closure.date.isoformat()} | {closure.weekday} | {_cell(closure.reason)} | "
        f"{closure.basis} | {' '.join(f'<{url}>' for url in closure.source_urls)} |"
        for closure in calendar.closures
    ]
    return lines


def _cell(text: str) -> str:
    return " ".join(text.split()).replace("|", "\\|")
