"""Diff and corroborate: pure functions over what the database already holds."""

from collections.abc import Iterable, Mapping
from dataclasses import dataclass
from datetime import date

from settlecal_pipeline.models import Basis, Corroboration, Observation

# The source that proposed a change plus one more that agrees.
MIN_INDEPENDENT_SOURCES = 2

type EntryKey = tuple[str, date]


def entry_key(observation: Observation) -> EntryKey:
    return (observation.calendar_id, observation.entry_date)


@dataclass(frozen=True, slots=True)
class PublishedEntry:
    """The current published version of a date."""

    is_open: bool
    reason: str
    basis: Basis


@dataclass(frozen=True, slots=True)
class PendingCandidate:
    id: int
    is_open: bool


@dataclass(frozen=True, slots=True)
class NewCandidate:
    calendar_id: str
    entry_date: date
    before: PublishedEntry | None
    after: Observation


@dataclass(frozen=True, slots=True)
class DiffPlan:
    """What a snapshot's observations change. Applying it twice changes nothing more."""

    new_candidates: tuple[NewCandidate, ...]
    # Observations that support a candidate (new or pending) for their date.
    supporting: tuple[Observation, ...]
    # Observations that contradict a pending candidate; logged, never linked.
    conflicting: tuple[Observation, ...]


def plan_diff(
    observations: Iterable[Observation],
    published: Mapping[EntryKey, PublishedEntry],
    pending: Mapping[EntryKey, PendingCandidate],
    operator_calendars: frozenset[str] = frozenset(),
) -> DiffPlan:
    """Compares observations with published entries and open candidates.

    Only open/closed counts as a difference: sources word the same holiday differently.
    An "open" observation for a date with no entry is no change, because days are open
    unless an entry or the calendar's weekend rule says otherwise. The one exception is a
    projected entry that its operator now confirms: that proposes the same state with
    basis=published. ``operator_calendars`` lists the calendars whose operator runs this
    snapshot's source.
    """
    by_date: dict[EntryKey, list[Observation]] = {}
    for observation in observations:
        by_date.setdefault(entry_key(observation), []).append(observation)

    new_candidates: list[NewCandidate] = []
    supporting: list[Observation] = []
    conflicting: list[Observation] = []
    for key, rows in by_date.items():
        candidate = pending.get(key)
        if candidate is not None:
            proposed_open = candidate.is_open
        else:
            current = published.get(key)
            confirms = key[0] in operator_calendars
            changed = [row for row in rows if not _matches_published(row, current, confirms)]
            if not changed:
                continue
            new_candidates.append(NewCandidate(key[0], key[1], before=current, after=changed[0]))
            proposed_open = changed[0].is_open
        # Judged per date, not per row, so row order in the snapshot doesn't matter.
        for row in rows:
            (supporting if row.is_open == proposed_open else conflicting).append(row)
    return DiffPlan(tuple(new_candidates), tuple(supporting), tuple(conflicting))


def _matches_published(
    observation: Observation, current: PublishedEntry | None, from_operator: bool
) -> bool:
    if current is None:
        return observation.is_open
    if current.is_open != observation.is_open:
        return False
    return not (from_operator and current.basis == Basis.PROJECTED)


def corroboration(independent_sources: int) -> Corroboration:
    """A candidate is ready for review once a second, independent source agrees."""
    if independent_sources >= MIN_INDEPENDENT_SOURCES:
        return Corroboration.CORROBORATED
    return Corroboration.SINGLE_SOURCE
