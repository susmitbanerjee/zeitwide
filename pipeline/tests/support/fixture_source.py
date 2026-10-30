"""A stand-in source whose page is an HTML table of dates, like many official pages.

It exists only in tests; real sources live in ``zeitwide_pipeline/sources/catalog/``.
"""

from dataclasses import dataclass, field
from datetime import date, timedelta
from html.parser import HTMLParser

from zeitwide_pipeline.models import Observation, SnapshotMeta, SourceRole
from zeitwide_pipeline.sources import ParseError
from zeitwide_pipeline.sources.base import DocumentLink

EXPECTED_HEADER = ("Date", "Holiday", "Status")
STATUS_IS_OPEN = {"Closed": False, "Open": True}


class _TableReader(HTMLParser):
    """Collects the cell text of ``<table id="holidays">``."""

    def __init__(self) -> None:
        super().__init__()
        self.rows: list[list[str]] = []
        self.found_table = False
        self._in_table = False
        self._cell: list[str] | None = None

    def handle_starttag(self, tag: str, attrs: list[tuple[str, str | None]]) -> None:
        if tag == "table" and ("id", "holidays") in attrs:
            self._in_table = self.found_table = True
        elif self._in_table and tag == "tr":
            self.rows.append([])
        elif self._in_table and tag in {"td", "th"}:
            self._cell = []

    def handle_endtag(self, tag: str) -> None:
        if tag == "table":
            self._in_table = False
        elif tag in {"td", "th"} and self._cell is not None and self.rows:
            self.rows[-1].append("".join(self._cell).strip())
            self._cell = None

    def handle_data(self, data: str) -> None:
        if self._cell is not None:
            self._cell.append(data)


@dataclass(frozen=True, slots=True)
class HolidayTableParser:
    calendar_id: str
    name: str = "holiday_table"
    version: str = "1"

    def parse(self, content: bytes, meta: SnapshotMeta) -> list[Observation]:
        reader = _TableReader()
        try:
            reader.feed(content.decode("utf-8"))
        except UnicodeDecodeError as error:
            raise ParseError(f"{meta.url} is not UTF-8") from error
        if not reader.found_table or not reader.rows:
            raise ParseError(f"No holidays table in {meta.url}")
        header, *body = reader.rows
        if tuple(header) != EXPECTED_HEADER:
            raise ParseError(f"Unexpected header {header!r} in {meta.url}")
        return [self._row(cells, meta) for cells in body]

    def _row(self, cells: list[str], meta: SnapshotMeta) -> Observation:
        if len(cells) != len(EXPECTED_HEADER) or cells[2] not in STATUS_IS_OPEN:
            raise ParseError(f"Unexpected row {cells!r} in {meta.url}")
        try:
            entry_date = date.fromisoformat(cells[0])
        except ValueError as error:
            raise ParseError(f"Unexpected date {cells[0]!r} in {meta.url}") from error
        return Observation(self.calendar_id, entry_date, STATUS_IS_OPEN[cells[2]], cells[1])


@dataclass(frozen=True, slots=True)
class HolidayTableSource:
    id: str
    url: str
    parser: HolidayTableParser
    publisher: str = "Fixture Publisher"
    schedule: timedelta = timedelta(days=1)
    role: SourceRole = SourceRole.PRIMARY
    content_types: frozenset[str] = field(default_factory=lambda: frozenset({"text/html"}))
    document: DocumentLink | None = None


def holiday_page(*rows: tuple[str, str, str]) -> bytes:
    cells = "".join(
        f"<tr><td>{day}</td><td>{name}</td><td>{status}</td></tr>" for day, name, status in rows
    )
    return (
        "<html><body><table id='holidays'><tr><th>Date</th><th>Holiday</th><th>Status</th></tr>"
        f"{cells}</table></body></html>"
    ).encode()
