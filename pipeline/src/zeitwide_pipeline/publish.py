"""Publishing a reviewed change: what kind of change it is, its basis, and its event.

Pure functions only; ``review_db`` applies them in one transaction.
"""

from collections.abc import Iterable
from dataclasses import dataclass
from datetime import date, datetime

from zeitwide_pipeline.models import Basis, ChangeType

URGENT_NOTICE_DAYS = 14


@dataclass(frozen=True, slots=True)
class EntryState:
    """One side of a change: what a date looked like before or after."""

    is_open: bool
    reason: str
    basis: Basis

    def to_json(self) -> dict[str, object]:
        return {"is_open": self.is_open, "reason": self.reason, "basis": self.basis.value}


def basis_for(supporting_sources: Iterable[str], operator_sources: Iterable[str]) -> Basis:
    """Published when the calendar's operator listed the date, projected otherwise."""
    return Basis.PUBLISHED if set(supporting_sources) & set(operator_sources) else Basis.PROJECTED


def change_type(before: EntryState | None, after: EntryState) -> ChangeType:
    """Classifies a new version against the current one.

    Raises ValueError when nothing changed, since publishing that would only add noise.
    """
    was_closed = before is not None and not before.is_open
    if not after.is_open:
        if not was_closed:
            return ChangeType.CLOSURE_ADDED
        if before is not None and before.reason != after.reason:
            return ChangeType.CLOSURE_CHANGED
    elif was_closed:
        return ChangeType.CLOSURE_REMOVED
    if before is not None and before.basis != after.basis:
        return ChangeType.BASIS_CHANGED
    raise ValueError("The new version is the same as the current one")


def notice_days(entry_date: date, published_on: date) -> int:
    """Days between publishing and the date; negative when the change comes late."""
    return (entry_date - published_on).days


def is_urgent(entry_date: date, today: date) -> bool:
    return notice_days(entry_date, today) < URGENT_NOTICE_DAYS


@dataclass(frozen=True, slots=True)
class ChangeEvent:
    """What the outbox carries to webhook subscribers."""

    event_id: int
    change: ChangeType
    calendar_id: str
    entry_date: date
    before: EntryState | None
    after: EntryState
    revision: int
    published_at: datetime

    def payload(self) -> dict[str, object]:
        return {
            "event_id": self.event_id,
            "type": self.change.value,
            "calendar_id": self.calendar_id,
            "date": self.entry_date.isoformat(),
            "before": self.before.to_json() if self.before else None,
            "after": self.after.to_json(),
            "revision": self.revision,
            "published_at": self.published_at.isoformat(),
        }
