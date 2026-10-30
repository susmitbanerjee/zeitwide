"""OPM's federal holiday schedule: one table per year, each row an observed date and holiday.

Inauguration Day is skipped: it is a holiday only for federal employees in the
Washington, DC, area, not a nationwide federal holiday. Tables for years before the one
preceding the fetch are skipped too: they can't change anything, and the archive has
errors (the 2011 table prints Monday 26 December 2011 as a Tuesday).
"""

import re
from dataclasses import dataclass
from datetime import date, datetime

from zeitwide_pipeline.models import Observation, SnapshotMeta, SourceRole
from zeitwide_pipeline.sources.base import ParseError, SourceDefinition
from zeitwide_pipeline.sources.html_tables import Table, read_page

CALENDAR_ID = "US-FEDERAL"
REGIONAL_HOLIDAYS = frozenset({"Inauguration Day"})
MIN_HOLIDAYS_PER_YEAR = 10
MIN_YEARS = 2
CELLS_PER_ROW = 2
DECEMBER = 12
_CAPTION = re.compile(r"^(?P<year>\d{4}) Holiday Schedule$")
_DAY = re.compile(
    r"^(?P<weekday>[A-Z][a-z]+), (?P<month>[A-Z][a-z]+) (?P<day>\d{1,2})(?:, (?P<year>\d{4}))?$"
)


@dataclass(frozen=True, slots=True)
class OpmHolidayParser:
    name: str = "opm_federal_holidays"
    version: str = "1"

    def parse(self, content: bytes, meta: SnapshotMeta) -> list[Observation]:
        first_year = meta.fetched_at.year - 1
        tables = [
            (int(match["year"]), table)
            for table in read_page(content, meta.url).tables
            if (match := _CAPTION.match(table.caption)) and int(match["year"]) >= first_year
        ]
        if len(tables) < MIN_YEARS:
            raise ParseError(f"Expected a holiday table per year in {meta.url}")
        observations: list[Observation] = []
        for year, table in tables:
            year_observations = _year(year, table, meta.url)
            if len(year_observations) < MIN_HOLIDAYS_PER_YEAR:
                raise ParseError(f"Only {len(year_observations)} holidays for {year} in {meta.url}")
            observations.extend(year_observations)
        return observations


def _year(year: int, table: Table, url: str) -> list[Observation]:
    observations = []
    for row in table.rows:
        if len(row) != CELLS_PER_ROW or row == ["Date", "Holiday"]:
            continue
        day, holiday = row[0].rstrip("* ").strip(), row[1].rstrip("* ").strip()
        if holiday in REGIONAL_HOLIDAYS:
            continue
        observations.append(Observation(CALENDAR_ID, _date(day, year, url), False, holiday))
    return observations


def _date(day: str, year: int, url: str) -> date:
    """Parses ``Monday, January 01`` or ``Friday, December 31, 2010``; checks the weekday.

    A New Year's Day on a Saturday is observed on 31 December, which OPM lists in the new
    year's table, so a December date may belong to the year before.
    """
    match = _DAY.match(day)
    if match is None:
        raise ParseError(f"Unexpected date {day!r} for {year} in {url}")
    years = [int(match["year"])] if match["year"] else [year, year - 1]
    for candidate_year in years:
        try:
            parsed = datetime.strptime(  # noqa: DTZ007 - a date, no time
                f"{match['month']} {match['day']} {candidate_year}", "%B %d %Y"
            ).date()
        except ValueError as error:
            raise ParseError(f"Unexpected date {day!r} for {year} in {url}") from error
        in_range = parsed.year == year or parsed.month == DECEMBER
        if in_range and f"{parsed:%A}" == match["weekday"]:
            return parsed
    raise ParseError(f"{day!r} doesn't fall on a {match['weekday']} near {year} in {url}")


SOURCE = SourceDefinition(
    id="opm-federal-holidays",
    publisher="U.S. Office of Personnel Management",
    url="https://www.opm.gov/policy-data-oversight/pay-leave/federal-holidays/",
    parser=OpmHolidayParser(),
    content_types=frozenset({"text/html"}),
    role=SourceRole.PRIMARY,
)
