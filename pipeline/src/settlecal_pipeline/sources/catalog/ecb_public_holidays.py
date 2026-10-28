"""ECB public holidays. Those starred are TARGET closing days, when T2 is closed.

The page lists every ECB holiday; T2 stays open on the unstarred weekdays (Ascension Day, Day
of German Unity and so on), so those become "open" observations.
"""

from dataclasses import dataclass
from datetime import date, datetime

from settlecal_pipeline.models import Observation, SnapshotMeta, SourceRole
from settlecal_pipeline.sources.base import ParseError, SourceDefinition
from settlecal_pipeline.sources.html_tables import read_page

CALENDAR_ID = "EU-T2"
CLOSING_MARKER = "*"
LEGEND = "* : TARGET closing day"
# New Year's Day, Good Friday, Easter Monday, 1 May, 25 and 26 December.
CLOSING_DAYS_PER_YEAR = 6
CELLS_PER_ROW = 2
SATURDAY = 5


@dataclass(frozen=True, slots=True)
class EcbHolidayParser:
    name: str = "ecb_public_holidays"
    version: str = "1"

    def parse(self, content: bytes, meta: SnapshotMeta) -> list[Observation]:
        page = read_page(content, meta.url)
        if LEGEND not in page.text:
            raise ParseError(f"Legend {LEGEND!r} is missing from {meta.url}")
        observations: list[Observation] = []
        for table in page.tables:
            rows = [row for row in table.rows if len(row) == CELLS_PER_ROW]
            year_observations = [_observation(name, day, meta.url) for name, day in rows]
            years = {o.entry_date.year for o in year_observations}
            closed = [o for o in year_observations if not o.is_open]
            if len(years) != 1 or len(closed) != CLOSING_DAYS_PER_YEAR:
                raise ParseError(
                    f"Expected one year with {CLOSING_DAYS_PER_YEAR} TARGET closing days per table"
                    f" in {meta.url}, got years {sorted(years)} with {len(closed)} closing days"
                )
            # T2 is closed every weekend, so an ECB holiday on one says nothing about T2.
            observations.extend(
                o for o in year_observations if not o.is_open or o.entry_date.weekday() < SATURDAY
            )
        return observations


def _observation(name: str, day: str, url: str) -> Observation:
    closing = name.endswith(CLOSING_MARKER)
    holiday = name.rstrip(CLOSING_MARKER).strip()
    try:
        entry_date: date = datetime.strptime(day, "%d %B %Y").date()  # noqa: DTZ007 - a date, no time
    except ValueError as error:
        raise ParseError(f"Unexpected date {day!r} for {holiday!r} in {url}") from error
    reason = f"{holiday} (TARGET closing day)" if closing else f"{holiday}: ECB holiday, T2 open"
    return Observation(CALENDAR_ID, entry_date, not closing, reason)


SOURCE = SourceDefinition(
    id="ecb-public-holidays",
    publisher="European Central Bank",
    url="https://www.ecb.europa.eu/ecb/contacts/working-hours/html/index.en.html",
    parser=EcbHolidayParser(),
    content_types=frozenset({"text/html"}),
    role=SourceRole.PRIMARY,
)
