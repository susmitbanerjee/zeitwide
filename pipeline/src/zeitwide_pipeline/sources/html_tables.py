"""Reads the tables of an HTML page as text. Pure: bytes in, cells out."""

import re
from dataclasses import dataclass, field
from html.parser import HTMLParser

from zeitwide_pipeline.sources.base import ParseError

_CELL_TAGS = frozenset({"td", "th"})
_SKIPPED_TAGS = frozenset({"script", "style"})
_WHITESPACE = re.compile(r"\s+")
RIGHT_SINGLE_QUOTE = "\u2019"
NO_BREAK_SPACE = "\xa0"


@dataclass
class Table:
    caption: str = ""
    rows: list[list[str]] = field(default_factory=list)


class _TableReader(HTMLParser):
    def __init__(self) -> None:
        super().__init__(convert_charrefs=True)
        self.tables: list[Table] = []
        self.text: list[str] = []
        self._open: list[Table] = []
        self._cell: list[str] | None = None
        self._caption: list[str] | None = None
        self._skipping = 0

    def handle_starttag(self, tag: str, _attrs: list[tuple[str, str | None]]) -> None:
        if tag in _SKIPPED_TAGS:
            self._skipping += 1
        elif tag == "table":
            self._open.append(Table())
        elif not self._open:
            return
        elif tag == "tr":
            self._open[-1].rows.append([])
        elif tag in _CELL_TAGS:
            self._cell = []
        elif tag == "caption":
            self._caption = []
        elif tag == "br" and self._cell is not None:
            self._cell.append(" ")

    def handle_endtag(self, tag: str) -> None:
        if tag in _SKIPPED_TAGS:
            self._skipping = max(0, self._skipping - 1)
        elif tag == "table" and self._open:
            self.tables.append(self._open.pop())
        elif tag in _CELL_TAGS and self._cell is not None and self._open:
            table = self._open[-1]
            if not table.rows:
                table.rows.append([])
            table.rows[-1].append(clean("".join(self._cell)))
            self._cell = None
        elif tag == "caption" and self._caption is not None and self._open:
            self._open[-1].caption = clean("".join(self._caption))
            self._caption = None

    def handle_data(self, data: str) -> None:
        if self._skipping:
            return
        self.text.append(data)
        if self._cell is not None:
            self._cell.append(data)
        elif self._caption is not None:
            self._caption.append(data)


@dataclass(frozen=True)
class Page:
    tables: list[Table]
    # All visible text, whitespace collapsed, for checking legends and footnotes.
    text: str


def read_page(content: bytes, url: str) -> Page:
    try:
        markup = content.decode("utf-8")
    except UnicodeDecodeError as error:
        raise ParseError(f"{url} is not UTF-8") from error
    reader = _TableReader()
    reader.feed(markup)
    reader.close()
    return Page([table for table in reader.tables if table.rows], clean("".join(reader.text)))


def clean(text: str) -> str:
    """Collapses whitespace and straightens typographic apostrophes."""
    straight = text.replace(RIGHT_SINGLE_QUOTE, "'").replace(NO_BREAK_SPACE, " ")
    return _WHITESPACE.sub(" ", straight).strip()
