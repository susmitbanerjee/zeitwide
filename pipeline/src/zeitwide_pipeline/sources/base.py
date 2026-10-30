"""What every source module provides."""

from dataclasses import dataclass
from datetime import timedelta
from typing import Protocol

from zeitwide_pipeline.models import Observation, SnapshotMeta, SourceRole

DAILY = timedelta(days=1)


class ParseError(Exception):
    """A snapshot didn't have the layout its parser expects, or held no rows."""


class Parser(Protocol):
    """Turns a snapshot's bytes into observations. Must be pure: no I/O, no clock."""

    @property
    def name(self) -> str: ...

    @property
    def version(self) -> str:
        """Bump on any change to what the parser emits; stored with every observation."""
        ...

    def parse(self, content: bytes, meta: SnapshotMeta) -> list[Observation]:
        """Raises ParseError when the layout isn't the one expected."""
        ...


class Source(Protocol):
    """One official page or file the pipeline monitors."""

    @property
    def id(self) -> str:
        """Stable slug, such as ``fed-holiday-schedule``; the ``source.slug`` column."""
        ...

    @property
    def publisher(self) -> str: ...

    @property
    def url(self) -> str: ...

    @property
    def schedule(self) -> timedelta:
        """How long to wait after one check before the next."""
        ...

    @property
    def role(self) -> SourceRole: ...

    @property
    def content_types(self) -> frozenset[str]:
        """Media types the parser accepts, such as ``text/html``."""
        ...

    @property
    def parser(self) -> Parser: ...

    @property
    def document(self) -> "DocumentLink | None":
        """Set when ``url`` is a stable page that links to the document to parse."""
        ...


class DocumentLink(Protocol):
    """Finds the current document on a source's stable page, for sources such as the
    Bundesbank's, whose file URL changes with each edition."""

    @property
    def page_types(self) -> frozenset[str]:
        """Media types of the stable page itself."""
        ...

    def find(self, page: bytes, page_url: str) -> str:
        """The document's absolute URL. Pure; raises ParseError if the link is missing."""
        ...


def parse_snapshot(parser: Parser, content: bytes, meta: SnapshotMeta) -> list[Observation]:
    """Runs a parser and rejects an empty result, so a broken page never looks unchanged."""
    observations = parser.parse(content, meta)
    if not observations:
        raise ParseError(f"{parser.name} {parser.version} found no rows in {meta.url}")
    return observations


@dataclass(frozen=True, slots=True)
class SourceDefinition:
    """The usual way a catalog module defines its ``SOURCE``."""

    id: str
    publisher: str
    url: str
    parser: Parser
    content_types: frozenset[str]
    role: SourceRole = SourceRole.PRIMARY
    schedule: timedelta = DAILY
    document: DocumentLink | None = None
