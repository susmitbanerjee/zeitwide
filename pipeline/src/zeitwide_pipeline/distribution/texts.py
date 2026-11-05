"""README, CITATION.cff and the Hugging Face dataset card, written from the release.

Nothing here is edited by hand: every figure is counted from the release, and every
calendar links to its canonical page on the website.
"""

import json
from typing import TYPE_CHECKING

from huggingface_hub import DatasetCardData

from zeitwide_pipeline.distribution.tables import (
    INDEX_NAME,
    calendar_url,
    coverage_years,
    csv_name,
    json_name,
    parquet_name,
)
from zeitwide_pipeline.release import LICENSE
from zeitwide_pipeline.site.downloads import LICENSE_URL
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.release_view import CalendarView, ReleaseView

if TYPE_CHECKING:
    from zeitwide_pipeline.distribution.bundle import Destinations

TITLE = "Zeitwide payment-rail calendars"
PYPI_URL = "https://pypi.org/project/zeitwide/"
LEGAL_CODE_URL = "https://creativecommons.org/licenses/by-nc/4.0/legalcode"
SPDX = "CC-BY-NC-4.0"
CREDIT = "Business-day data from Zeitwide (https://zeitwide.com), CC BY-NC 4.0."
_SIZE_STEPS = (
    (1_000, "n<1K"),
    (10_000, "1K<n<10K"),
    (100_000, "10K<n<100K"),
    (1_000_000, "100K<n<1M"),
)
_KEYWORDS = [
    "business days",
    "bank holidays",
    "payment rails",
    "fedwire",
    "fedach",
    "fednow",
    "target2",
    "chaps",
    "holiday calendar",
]

LICENSE_TEXT = f"""Zeitwide dataset
================

These files are Copyright (c) 2026 Zeitwide and licensed under the Creative Commons
Attribution-NonCommercial 4.0 International License ({LICENSE}).

  Summary:    {LICENSE_URL}
  Legal code: {LEGAL_CODE_URL}

You may share and adapt the data for non-commercial purposes, if you give appropriate
credit, link to the license and say whether you made changes. Suggested credit:

  {CREDIT}

Each entry names the official sources it was built from. Commercial use of the data
needs a separate agreement, or the hosted Zeitwide API under its terms of service.
"""


def github_url(destinations: "Destinations") -> str:
    return f"https://github.com/{destinations.github_repository}"


def hub_url(destinations: "Destinations") -> str:
    return f"https://huggingface.co/datasets/{destinations.hub_repository}"


def size_category(rows: int) -> str:
    for limit, label in _SIZE_STEPS:
        if rows < limit:
            return label
    return "1M<n<10M"


def total_closures(release: ReleaseView) -> int:
    return sum(len(calendar.closures) for calendar in release.calendars)


def total_rows(release: ReleaseView) -> int:
    return sum(len(calendar.entries) for calendar in release.calendars)


def summary(release: ReleaseView) -> str:
    """The one paragraph both READMEs open with."""
    ids = ", ".join(calendar.id for calendar in release.calendars)
    return (
        f"Zeitwide publishes the days on which {len(release.calendars)} payment-rail and "
        f"public-holiday calendars are closed ({ids}), with the reason for each, whether the "
        "operator published the date or Zeitwide projected it, and the official sources behind it. "
        f"Release {release.id} lists {total_closures(release)} closures as of "
        f"{release.today.isoformat()}. Every row comes from official publications and is "
        "released only after two independent sources agree and a human reviewer signs off. "
        f"The data is licensed {LICENSE}."
    )


def github_readme(release: ReleaseView, links: SiteLinks, destinations: "Destinations") -> str:
    rows = [
        f"| [{calendar.id}]({calendar_url(calendar, links)}) | {calendar.name} | "
        f"{len(calendar.closures)} | {_years(calendar)} | "
        f"[CSV]({csv_name(calendar.id)}) · [JSON]({json_name(calendar.id)}) |"
        for calendar in release.calendars
    ]
    return "\n".join(
        [
            f"# {TITLE}",
            "",
            summary(release),
            "",
            "| Calendar | Name | Closures | Years | Files |",
            "| --- | --- | ---: | --- | --- |",
            *rows,
            "",
            "Each calendar name links to its canonical page on "
            f"[{links.site_url.rstrip('/')}]({links.page('/')}), which shows the same dates "
            "with their evidence and answers any date. "
            f"[{INDEX_NAME}]({INDEX_NAME}) lists the calendars with their rules and pages.",
            "",
            "## Files",
            "",
            "Per calendar, `data/<ID>.csv` and `data/<ID>.json` hold the same rows: `date`, "
            "`weekday`, `open`, `reason`, `basis` (`published` or `projected`), `sources` "
            "(official URLs, space-separated in the CSV) and `revision` (how often the "
            "date has been revised). A date with no row is open unless its weekday is in the "
            "calendar's `weekend`, which the JSON file and "
            f"[{INDEX_NAME}]({INDEX_NAME}) give.",
            "",
            "[CHANGELOG.md](CHANGELOG.md) lists every change to the data. "
            "`MANIFEST.json` and `SHA256SUMS` carry the checksum of every file and of the "
            f"release they were built from. Each release is tagged `{release.id}`.",
            "",
            *_provenance(),
            "",
            "## Elsewhere",
            "",
            f"- Hugging Face: [{destinations.hub_repository}]({hub_url(destinations)}) "
            "(the same data as Parquet)",
            f"- Python library, answers business-day questions offline: [zeitwide]({PYPI_URL})",
            f"- REST API and MCP server: {links.page('/')}",
            "",
            *_citation(release, links, destinations),
            "",
        ]
    )


def citation_cff(release: ReleaseView, links: SiteLinks, destinations: "Destinations") -> str:
    def q(text: str) -> str:
        return json.dumps(text, ensure_ascii=False)

    return "\n".join(
        [
            "cff-version: 1.2.0",
            'message: "If you use this dataset, please cite it as below."',
            "type: dataset",
            f"title: {q(TITLE)}",
            f"version: {q(release.id)}",
            f"date-released: {release.today.isoformat()}",
            f"license: {SPDX}",
            f"url: {q(links.page('/'))}",
            f"repository: {q(github_url(destinations))}",
            f"abstract: {q(summary(release))}",
            "authors:",
            '  - name: "Zeitwide"',
            "keywords:",
            *(f"  - {q(keyword)}" for keyword in _KEYWORDS),
            "",
        ]
    )


def card_data(release: ReleaseView) -> DatasetCardData:
    return DatasetCardData(
        language="en",
        license="cc-by-nc-4.0",
        pretty_name=TITLE,
        size_categories=[size_category(total_rows(release))],
        task_categories=["other"],
        tags=["calendar", "holidays", "business-days", "payments", "finance"],
        configs=[
            {
                "config_name": calendar.id,
                "data_files": [{"split": "train", "path": parquet_name(calendar.id)}],
            }
            for calendar in release.calendars
        ],
    )


def dataset_card(release: ReleaseView, links: SiteLinks, destinations: "Destinations") -> str:
    rows = [
        f"| [{calendar.id}]({calendar_url(calendar, links)}) | {calendar.name} | "
        f"{len(calendar.closures)} | {_years(calendar)} | `{parquet_name(calendar.id)}` |"
        for calendar in release.calendars
    ]
    return "\n".join(
        [
            "---",
            card_data(release).to_yaml().rstrip(),
            "---",
            "",
            f"# {TITLE}",
            "",
            "## Summary",
            "",
            summary(release),
            "",
            "Each calendar is its own configuration, so "
            f'`load_dataset("{destinations.hub_repository}", "US-FEDWIRE")` loads one.',
            "",
            "| Calendar | Name | Closures | Years | File |",
            "| --- | --- | ---: | --- | --- |",
            *rows,
            "",
            "Each calendar name links to its canonical page on Zeitwide, which shows the same "
            "dates with their evidence.",
            "",
            "## Structure",
            "",
            "Columns: `date`, `weekday`, `open` (false for a closure), `reason`, `basis` "
            "(`published` when the calendar's operator listed the date, `projected` when "
            "Zeitwide worked it out from the operator's rules), `sources` (a list of official "
            "URLs) and `revision` (how often the date has been revised). A date with no row "
            "is open unless its weekday is in the calendar's weekend, which "
            f"`{INDEX_NAME}` gives. The same data is also on GitHub as CSV and JSON "
            f"([{destinations.github_repository}]({github_url(destinations)})).",
            "",
            *_provenance(),
            "",
            *_citation(release, links, destinations),
            "",
        ]
    )


def _provenance() -> list[str]:
    return [
        "## How it is sourced",
        "",
        "Zeitwide monitors the official publications of each calendar's operator and of "
        "public bodies (central banks, payment-system operators and governments), keeps "
        "every fetched page, and parses it into observations. A change is published only "
        "after two independent sources agree and a human reviewer approves it. Every row "
        "lists the source URLs it rests on, and the database keeps every version of every "
        "date, so any past release can be reproduced.",
        "",
        "## Known limits",
        "",
        "- Coverage ends where the sources do: a calendar lists years only up to the last "
        "year an operator or public body has published, and the hosted API refuses to "
        "answer beyond it.",
        "- `projected` dates follow the operator's published rules and can be wrong until "
        "the operator publishes the year.",
        "- Weekends are not rows; use each calendar's `weekend`.",
        "- Opening hours, cut-off times and unscheduled closures are not covered.",
        "- This is not legal or financial advice. Check the sources before relying on a "
        "date for a payment.",
    ]


def _citation(release: ReleaseView, links: SiteLinks, destinations: "Destinations") -> list[str]:
    key = "zeitwide_calendars_" + release.id.replace(".", "_")
    year = release.today.year
    return [
        "## How to cite",
        "",
        f"{CREDIT} Release {release.id}. Say whether you made changes.",
        "",
        "```bibtex",
        f"@dataset{{{key},",
        f"  title   = {{{TITLE}}},",
        "  author  = {{Zeitwide}},",
        f"  year    = {{{year}}},",
        f"  version = {{{release.id}}},",
        f"  url     = {{{links.page('/')}}},",
        f"  note    = {{{LICENSE}; {github_url(destinations)}}}",
        "}",
        "```",
        "",
        "## License",
        "",
        f"[{LICENSE}]({LICENSE_URL}). Commercial use needs the hosted API or a separate agreement.",
    ]


def _years(calendar: CalendarView) -> str:
    first, last = coverage_years(calendar)
    if first is None:
        return "any (weekend rule only)" if not calendar.closes_on_holidays else "none yet"
    return str(first) if first == last else f"{first}-{last}"
