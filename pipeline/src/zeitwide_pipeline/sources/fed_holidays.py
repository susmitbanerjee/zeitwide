"""The Federal Reserve's holiday table, shared by frbservices.org and K.8.

Both pages publish one table: a row per holiday, a column per year, each cell a nominal
date such as ``July 4*``. One star marks a Saturday (the Reserve Banks open the Friday
before), two a Sunday (closed the Monday after). The parser checks every marker against
the weekday and applies each calendar's own observance rule, so a change in either the
page or the rule shows up as an error, not a wrong date.
"""

import re
from dataclasses import dataclass
from datetime import date

from zeitwide_pipeline.calendars import CalendarDefinition, load_catalog, observed_date
from zeitwide_pipeline.models import Observation, SnapshotMeta
from zeitwide_pipeline.sources.base import ParseError
from zeitwide_pipeline.sources.html_tables import Table, read_page
from zeitwide_pipeline.sources.months import MONTHS

FED_CALENDARS = ("US-FEDWIRE", "US-FEDACH", "US-FEDNOW")
MIN_YEARS = 3
MIN_HOLIDAYS = 10
SATURDAY_MARKER = "*"
SUNDAY_MARKER = "**"
FOOTNOTE_MARKER_LENGTH = 3
SATURDAY = 5
SUNDAY = 6
# Wording of the observance footnotes; if it changes, the rules may have changed too.
SATURDAY_LEGEND = (
    "holidays falling on Saturday, Federal Reserve Banks and Branches will be open the preceding"
    " Friday"
)
SUNDAY_LEGEND = "holidays falling on Sunday"
_YEAR = re.compile(r"^\d{4}$")
_CELL = re.compile(r"^(?P<month>[A-Z][a-z]+) (?P<day>\d{1,2})\s*(?P<marker>\*{0,3})$")


@dataclass(frozen=True, slots=True)
class FedHolidayTableParser:
    name: str
    version: str = "1"
    calendar_ids: tuple[str, ...] = FED_CALENDARS

    def parse(self, content: bytes, meta: SnapshotMeta) -> list[Observation]:
        page = read_page(content, meta.url)
        for legend in (SATURDAY_LEGEND, SUNDAY_LEGEND):
            if legend not in page.text:
                raise ParseError(f"Observance footnote {legend!r} is missing from {meta.url}")
        table, years = _holiday_table(page.tables, meta.url)
        catalog = load_catalog()
        calendars = [catalog[calendar_id] for calendar_id in self.calendar_ids]
        observations: list[Observation] = []
        holidays = 0
        for row in table.rows:
            if len(row) < len(years) + 1 or _is_header(row):
                continue
            holiday = row[0]
            if not holiday:
                raise ParseError(f"Holiday row without a name in {meta.url}: {row!r}")
            holidays += 1
            for year, cell in zip(years, row[-len(years) :], strict=True):
                nominal = _nominal_date(cell, year, page.text, meta.url)
                observations.extend(_observe(holiday, nominal, calendars))
        if holidays < MIN_HOLIDAYS:
            raise ParseError(f"Only {holidays} holidays in {meta.url}")
        return observations


def _holiday_table(tables: list[Table], url: str) -> tuple[Table, list[int]]:
    for table in tables:
        for row in table.rows:
            years = [int(cell) for cell in row if _YEAR.match(cell)]
            if len(years) >= MIN_YEARS and not any("Processing" in cell for cell in row):
                return table, years
    raise ParseError(f"No holiday table with a column per year in {url}")


def _is_header(row: list[str]) -> bool:
    return any(_YEAR.match(cell) for cell in row)


def _nominal_date(cell: str, year: int, page_text: str, url: str) -> date:
    match = _CELL.match(cell)
    if match is None or match["month"] not in MONTHS:
        raise ParseError(f"Unexpected date cell {cell!r} for {year} in {url}")
    try:
        nominal = date(year, MONTHS[match["month"]], int(match["day"]))
    except ValueError as error:
        raise ParseError(f"Impossible date {cell!r} for {year} in {url}") from error
    marker = match["marker"]
    # Three or more stars point to a one-off footnote about a weekday holiday (K.8 used
    # *** for MLK Day on Inauguration Day 2025); the Reserve Banks are still closed.
    if len(marker) >= FOOTNOTE_MARKER_LENGTH and nominal.weekday() < SATURDAY:
        if f"{marker} " not in page_text:
            raise ParseError(f"{cell!r} points to a {marker} footnote missing from {url}")
        return nominal
    expected = {SATURDAY: SATURDAY_MARKER, SUNDAY: SUNDAY_MARKER}.get(nominal.weekday(), "")
    if marker != expected:
        raise ParseError(
            f"{cell!r} in {year} falls on {nominal:%A} but is marked {match['marker']!r} in {url}"
        )
    return nominal


def _observe(holiday: str, nominal: date, calendars: list[CalendarDefinition]) -> list[Observation]:
    observations = []
    for calendar in calendars:
        on = observed_date(nominal, calendar.observance) if calendar.observance else nominal
        reason = holiday if on == nominal else f"{holiday} (observed)"
        if not calendar.closes_on_holidays:
            reason = f"{holiday}: {calendar.name} operates every day"
        observations.append(Observation(calendar.id, on, not calendar.closes_on_holidays, reason))
    return observations
