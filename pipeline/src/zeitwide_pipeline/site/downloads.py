"""Each calendar's current entries as CSV, JSON and iCalendar, for the calendar page's downloads.

Like the other site files they are pure functions of the release: an entry's version is the
one current at the release's ``as_of``, and every timestamp in them is that ``as_of``, so the
same release always gives the same bytes. They hold only what the release holds; the
weekend rule decides every other day, as the JSON's ``weekend`` says.
"""

import csv
import io
import json
from datetime import datetime, timedelta

from zeitwide_pipeline.release import LICENSE
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.paths import answer_path, calendar_path
from zeitwide_pipeline.site.release_view import CalendarView, ReleaseView

LICENSE_URL = "https://creativecommons.org/licenses/by-nc/4.0/"
CSV_TYPE = "text/csv; charset=utf-8"
JSON_TYPE = "application/json; charset=utf-8"
ICS_TYPE = "text/calendar; charset=utf-8"
FORMATS = ("csv", "json", "ics")
CSV_COLUMNS = ("date", "is_open", "reason", "basis", "source_urls")
# RFC 5545 3.1: lines longer than 75 octets are folded.
ICS_LINE_OCTETS = 75


def download_name(calendar_id: str, extension: str) -> str:
    """The site file's name, served at ``/downloads/{name}``."""
    return f"downloads/{calendar_id}.{extension}"


def calendar_csv(calendar: CalendarView) -> str:
    out = io.StringIO()
    writer = csv.writer(out, lineterminator="\r\n")
    writer.writerow(CSV_COLUMNS)
    for entry in calendar.entries:
        writer.writerow(
            (
                entry.date.isoformat(),
                "true" if entry.is_open else "false",
                entry.reason,
                entry.basis,
                " ".join(entry.source_urls),
            )
        )
    return out.getvalue()


def calendar_json(release: ReleaseView, calendar: CalendarView, links: SiteLinks) -> str:
    years = calendar.covered_years(release.build_years)
    coverage: dict[str, object] = (
        {"type": "unlimited"}
        if not calendar.closes_on_holidays
        else {"type": "none"}
        if not years
        else {"type": "years", "from": f"{years[0]}-01-01", "to": f"{years[-1]}-12-31"}
    )
    document = {
        "calendar": {
            "id": calendar.id,
            "name": calendar.name,
            "kind": calendar.kind,
            "timezone": calendar.timezone,
            "weekend": list(calendar.weekend),
            "closes_on_holidays": calendar.closes_on_holidays,
            "revision": calendar.revision,
            "coverage": coverage,
            "url": links.page(calendar_path(calendar.id)),
        },
        "dataset_release": release.id,
        "as_of": _instant(release.as_of),
        "license": {"name": LICENSE, "url": LICENSE_URL},
        "entries": [
            {
                "date": entry.date.isoformat(),
                "is_open": entry.is_open,
                "reason": entry.reason,
                "basis": entry.basis,
                "sources": list(entry.source_urls),
            }
            for entry in calendar.entries
        ],
    }
    return json.dumps(document, ensure_ascii=False, indent=2) + "\n"


def calendar_ics(release: ReleaseView, calendar: CalendarView, links: SiteLinks) -> str:
    """One all-day event per closure. Openings aren't events, so they're left out."""
    stamp = release.as_of.strftime("%Y%m%dT%H%M%SZ")
    lines = [
        "BEGIN:VCALENDAR",
        "VERSION:2.0",
        f"PRODID:-//Zeitwide//Release {release.id}//EN",
        "CALSCALE:GREGORIAN",
        f"X-WR-CALNAME:{_text(f'{calendar.name} closures')}",
    ]
    for closure in calendar.closures:
        day = closure.date
        lines += [
            "BEGIN:VEVENT",
            f"UID:{calendar.id}-{day.isoformat()}@zeitwide",
            f"DTSTAMP:{stamp}",
            f"DTSTART;VALUE=DATE:{day.strftime('%Y%m%d')}",
            f"DTEND;VALUE=DATE:{(day + timedelta(days=1)).strftime('%Y%m%d')}",
            f"SUMMARY:{_text(f'{calendar.name} closed: {closure.reason}')}",
            f"URL:{links.page(answer_path(calendar.id, day))}",
            "TRANSP:TRANSPARENT",
            "END:VEVENT",
        ]
    lines.append("END:VCALENDAR")
    return "".join(_fold(line) + "\r\n" for line in lines)


def _text(value: str) -> str:
    """An RFC 5545 TEXT value: backslash, semicolon, comma and newline escaped."""
    return value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace("\n", "\\n")


def _fold(line: str) -> str:
    """Splits a content line at 75 octets, never inside a UTF-8 character."""
    parts: list[str] = []
    current = ""
    limit = ICS_LINE_OCTETS
    for char in line:
        if len((current + char).encode()) > limit:
            parts.append(current)
            current = ""
            # Continuation lines start with a space, which counts toward their 75.
            limit = ICS_LINE_OCTETS - 1
        current += char
    parts.append(current)
    return "\r\n ".join(parts)


def _instant(moment: datetime) -> str:
    return moment.isoformat().replace("+00:00", "Z")
