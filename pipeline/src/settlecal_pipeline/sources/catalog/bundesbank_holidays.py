"""Deutsche Bundesbank, "Public holidays in Germany": nationwide holidays and TARGET holidays.

The Bundesbank publishes one PDF per year. Its URL changes with each edition, so the
source is the stable page ``.../holiday-calendar-764518`` (it redirects to the current
year's page) and the parser follows its PDF link.

Only the nationwide holidays make the DE calendar: the regional ones in the same file
vary by state. The TARGET holidays corroborate EU-T2, whose operator is the ECB.
"""

import re
from dataclasses import dataclass
from datetime import date
from urllib.parse import urljoin

from settlecal_pipeline.models import Observation, SnapshotMeta, SourceRole
from settlecal_pipeline.sources.base import ParseError, SourceDefinition
from settlecal_pipeline.sources.months import MONTHS
from settlecal_pipeline.sources.pdf_text import pdf_lines

DOCUMENT_ID = "764518"
DE = "DE"
EU_T2 = "EU-T2"
TARGET_HOLIDAYS = 6
MIN_NATIONWIDE_HOLIDAYS = 9
WEEKEND_RULE = "On Saturdays and Sundays"
_TITLE = re.compile(r"^Public holidays in Germany in (?P<year>\d{4})$")
_LINE = re.compile(r"^(?P<day>\d{1,2}) ?(?P<month>[A-Z][a-z]+) (?P<name>.+)$")
_PDF_LINK = re.compile(rf'href="(?P<path>/resource/blob/{DOCUMENT_ID}/[^"]+\.pdf)"')
# Headings in the order the PDF prints them.
TARGET_HEADING = "TARGET holidays"
T2S_HEADING = "TARGET2-Securities holidays"
NATIONWIDE_HEADING = "Public holidays throughout the Federal Republic of Germany"
REGIONAL_HEADING = "Additional public holidays in individual German federal states"


@dataclass(frozen=True, slots=True)
class BundesbankPdfLink:
    page_types: frozenset[str] = frozenset({"text/html"})

    def find(self, page: bytes, page_url: str) -> str:
        links = {str(m["path"]) for m in _PDF_LINK.finditer(page.decode("utf-8", "replace"))}
        if len(links) != 1:
            raise ParseError(f"Expected one holiday PDF link on {page_url}, found {len(links)}")
        return urljoin(page_url, links.pop())


@dataclass(frozen=True, slots=True)
class BundesbankHolidayParser:
    name: str = "bundesbank_public_holidays_pdf"
    version: str = "1"

    def parse(self, content: bytes, meta: SnapshotMeta) -> list[Observation]:
        lines = pdf_lines(content, meta.url)
        title = _TITLE.match(lines[0]) if lines else None
        if title is None:
            raise ParseError(f"No 'Public holidays in Germany in YYYY' title in {meta.url}")
        if not any(line.startswith(WEEKEND_RULE) for line in lines):
            raise ParseError(f"The weekend rule is missing from {meta.url}")
        year = int(title["year"])
        target = _section(lines, TARGET_HEADING, T2S_HEADING, year, meta.url)
        nationwide = _section(lines, NATIONWIDE_HEADING, REGIONAL_HEADING, year, meta.url)
        if len(target) != TARGET_HOLIDAYS or len(nationwide) < MIN_NATIONWIDE_HOLIDAYS:
            raise ParseError(
                f"Expected {TARGET_HOLIDAYS} TARGET and at least {MIN_NATIONWIDE_HOLIDAYS}"
                f" nationwide holidays in {meta.url}, got {len(target)} and {len(nationwide)}"
            )
        return [
            *(Observation(EU_T2, day, False, f"{name} (TARGET holiday)") for day, name in target),
            *(Observation(DE, day, False, name) for day, name in nationwide),
        ]


def _section(
    lines: list[str], heading: str, next_heading: str, year: int, url: str
) -> list[tuple[date, str]]:
    start = _index(lines, heading, url)
    end = _index(lines, next_heading, url)
    if end <= start:
        raise ParseError(f"{next_heading!r} comes before {heading!r} in {url}")
    return [_holiday(line, year, url) for line in lines[start + 1 : end]]


def _index(lines: list[str], heading: str, url: str) -> int:
    for index, line in enumerate(lines):
        if line.startswith(heading):
            return index
    raise ParseError(f"Heading {heading!r} is missing from {url}")


def _holiday(line: str, year: int, url: str) -> tuple[date, str]:
    match = _LINE.match(line)
    if match is None or match["month"] not in MONTHS:
        raise ParseError(f"Unexpected line {line!r} in {url}")
    try:
        return date(year, MONTHS[match["month"]], int(match["day"])), match["name"]
    except ValueError as error:
        raise ParseError(f"Impossible date in {line!r} in {url}") from error


SOURCE = SourceDefinition(
    id="bundesbank-holidays",
    publisher="Deutsche Bundesbank",
    url=f"https://www.bundesbank.de/en/tasks/payment-systems/target/holiday-calendar-{DOCUMENT_ID}",
    parser=BundesbankHolidayParser(),
    content_types=frozenset({"application/pdf"}),
    role=SourceRole.PRIMARY,
    document=BundesbankPdfLink(),
)
