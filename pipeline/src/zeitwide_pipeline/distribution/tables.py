"""One calendar's current entries as CSV, JSON and Parquet, and the index of all calendars.

Pure functions of the release, like the site files: an entry's version is the one current at
the release's ``as_of``, and the same release always gives the same bytes. The CSV files and
``calendars.csv`` hold nothing about the release itself, so they change only when the data
does; ``bundle.data_digest`` relies on that to skip a publish with nothing new.
"""

import csv
import io
import json
from dataclasses import dataclass
from datetime import date

import pyarrow as pa
import pyarrow.parquet as pq

from zeitwide_pipeline.release import LICENSE
from zeitwide_pipeline.site.downloads import LICENSE_URL
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.paths import calendar_path
from zeitwide_pipeline.site.release_view import WEEKDAYS, CalendarView, ReleaseView

COLUMNS = ("date", "weekday", "open", "reason", "basis", "sources", "revision")
INDEX_COLUMNS = (
    "calendar",
    "name",
    "kind",
    "timezone",
    "weekend",
    "closes_on_holidays",
    "closures",
    "first_year",
    "last_year",
    "revision",
    "url",
)
INDEX_NAME = "calendars.csv"
_SCHEMA = pa.schema(
    [
        ("date", pa.date32()),
        ("weekday", pa.string()),
        ("open", pa.bool_()),
        ("reason", pa.string()),
        ("basis", pa.string()),
        ("sources", pa.list_(pa.string())),
        ("revision", pa.int32()),
    ]
)


def csv_name(calendar_id: str) -> str:
    return f"data/{calendar_id}.csv"


def json_name(calendar_id: str) -> str:
    return f"data/{calendar_id}.json"


def parquet_name(calendar_id: str) -> str:
    return f"data/{calendar_id}.parquet"


@dataclass(frozen=True, slots=True)
class Row:
    date: date
    weekday: str
    open: bool
    reason: str
    basis: str
    sources: tuple[str, ...]
    revision: int


def rows(calendar: CalendarView) -> list[Row]:
    """The calendar's current entries in date order: closures, and openings on weekends."""
    return [
        Row(
            date=entry.date,
            weekday=WEEKDAYS[entry.date.weekday()],
            open=entry.is_open,
            reason=entry.reason,
            basis=entry.basis,
            sources=entry.source_urls,
            revision=entry.revision,
        )
        for entry in calendar.entries
    ]


def calendar_csv(calendar: CalendarView) -> bytes:
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(COLUMNS)
    for row in rows(calendar):
        writer.writerow(
            (
                row.date.isoformat(),
                row.weekday,
                "true" if row.open else "false",
                row.reason,
                row.basis,
                " ".join(row.sources),
                row.revision,
            )
        )
    return out.getvalue().encode()


def calendar_json(release: ReleaseView, calendar: CalendarView, links: SiteLinks) -> bytes:
    years = calendar.covered_years(release.build_years)
    document = {
        "calendar": {
            "id": calendar.id,
            "name": calendar.name,
            "kind": calendar.kind,
            "timezone": calendar.timezone,
            "weekend": list(calendar.weekend),
            "closes_on_holidays": calendar.closes_on_holidays,
            "revision": calendar.revision,
            "url": calendar_url(calendar, links),
        },
        "dataset_release": release.id,
        "as_of": release.as_of.isoformat().replace("+00:00", "Z"),
        "license": {"name": LICENSE, "url": LICENSE_URL},
        "coverage": _coverage(calendar, years),
        "entries": [
            {
                "date": row.date.isoformat(),
                "weekday": row.weekday,
                "open": row.open,
                "reason": row.reason,
                "basis": row.basis,
                "sources": list(row.sources),
                "revision": row.revision,
            }
            for row in rows(calendar)
        ],
    }
    return (json.dumps(document, ensure_ascii=False, indent=2) + "\n").encode()


def calendar_parquet(release: ReleaseView, calendar: CalendarView, links: SiteLinks) -> bytes:
    records = [
        {
            "date": row.date,
            "weekday": row.weekday,
            "open": row.open,
            "reason": row.reason,
            "basis": row.basis,
            "sources": list(row.sources),
            "revision": row.revision,
        }
        for row in rows(calendar)
    ]
    metadata = {
        "calendar": calendar.id,
        "calendar_url": calendar_url(calendar, links),
        "dataset_release": release.id,
        "license": LICENSE,
    }
    schema = _SCHEMA.with_metadata({key: value.encode() for key, value in metadata.items()})
    out = io.BytesIO()
    pq.write_table(pa.Table.from_pylist(records, schema=schema), out, compression="zstd")
    return out.getvalue()


def calendars_csv(release: ReleaseView, links: SiteLinks) -> bytes:
    """One row per calendar: its rules, how many closures it lists and its canonical page."""
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(INDEX_COLUMNS)
    for calendar in release.calendars:
        first, last = coverage_years(calendar)
        writer.writerow(
            (
                calendar.id,
                calendar.name,
                calendar.kind,
                calendar.timezone,
                " ".join(calendar.weekend),
                "true" if calendar.closes_on_holidays else "false",
                len(calendar.closures),
                first or "",
                last or "",
                calendar.revision,
                calendar_url(calendar, links),
            )
        )
    return out.getvalue().encode()


def calendar_url(calendar: CalendarView, links: SiteLinks) -> str:
    """The calendar's canonical page on the website."""
    return links.page(calendar_path(calendar.id))


def coverage_years(calendar: CalendarView) -> tuple[int | None, int | None]:
    """First and last year with an entry; both None for a calendar with none."""
    if not calendar.entry_years:
        return None, None
    return min(calendar.entry_years), max(calendar.entry_years)


def _coverage(calendar: CalendarView, years: list[int]) -> dict[str, object]:
    if not calendar.closes_on_holidays:
        return {"type": "unlimited"}
    if not years:
        return {"type": "none"}
    return {"type": "years", "from": f"{years[0]}-01-01", "to": f"{years[-1]}-12-31"}
