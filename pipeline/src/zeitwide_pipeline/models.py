"""Values that move between pipeline steps. Plain data, no I/O."""

from dataclasses import dataclass
from datetime import date, datetime
from enum import StrEnum

SHA256_HEX_LENGTH = 64


class SourceRole(StrEnum):
    """How much a source counts for on its own."""

    PRIMARY = "primary"
    CORROBORATING = "corroborating"


class Basis(StrEnum):
    """Whether the calendar's operator published a date, or Zeitwide projected it."""

    PUBLISHED = "published"
    PROJECTED = "projected"


class ChangeType(StrEnum):
    """The ``change_type`` domain in the database."""

    CLOSURE_ADDED = "closure_added"
    CLOSURE_REMOVED = "closure_removed"
    CLOSURE_CHANGED = "closure_changed"
    BASIS_CHANGED = "basis_changed"


class Corroboration(StrEnum):
    """Whether a candidate change is backed by enough independent sources."""

    SINGLE_SOURCE = "single_source"
    CORROBORATED = "corroborated"


@dataclass(frozen=True, slots=True)
class Observation:
    """What one source says about one date on one calendar."""

    calendar_id: str
    entry_date: date
    is_open: bool
    reason: str


@dataclass(frozen=True, slots=True)
class SnapshotMeta:
    """Context a parser gets alongside the raw bytes."""

    source_id: str
    url: str
    fetched_at: datetime
    content_type: str
    sha256: str

    def __post_init__(self) -> None:
        if self.fetched_at.tzinfo is None:
            raise ValueError("fetched_at must be timezone-aware")
        if len(self.sha256) != SHA256_HEX_LENGTH:
            raise ValueError("sha256 must be 64 hex characters")
