"""Reading a Zeitwide dataset release (``dataset/schema/release.schema.json``).

This module only reads data. The rules that turn it into answers are in
``zeitwide.engine``, as specified in ``dataset/README.md``.
"""

import json
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import date, datetime
from enum import StrEnum
from pathlib import Path

WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
# Version 1 had only the entries current at as_of; each reads as if never replaced.
SCHEMA_VERSIONS = (1, 2)


class ReleaseError(ValueError):
    """The release file isn't one this version of the library can read."""


class UnknownCalendarError(ReleaseError):
    """The release has no calendar with this ID."""

    def __init__(self, release_id: str, calendar_id: str) -> None:
        super().__init__(f"Release {release_id} has no calendar {calendar_id!r}")
        self.calendar_id = calendar_id


class Basis(StrEnum):
    """Whether the operator published a date, or Zeitwide projected it from other sources."""

    PUBLISHED = "published"
    PROJECTED = "projected"


@dataclass(frozen=True, slots=True)
class SourceRef:
    """A source snapshot that backed an entry version."""

    source_id: str
    publisher: str
    url: str
    sha256: str
    fetched_at: datetime


@dataclass(frozen=True, slots=True)
class Entry:
    """One version of a day that differs from the weekend rule: a closure, or an open day.

    It was Zeitwide's view from ``recorded_from`` (inclusive) to ``recorded_to``
    (exclusive), or onwards when ``recorded_to`` is None.
    """

    day: date
    is_open: bool
    reason: str
    basis: Basis
    revision: int
    recorded_from: datetime
    recorded_to: datetime | None
    sources: tuple[SourceRef, ...]

    def recorded_at(self, moment: datetime) -> bool:
        return self.recorded_from <= moment and (
            self.recorded_to is None or moment < self.recorded_to
        )


@dataclass(frozen=True, slots=True)
class Calendar:
    id: str
    name: str
    timezone: str
    # Weekday numbers as in ``date.weekday()``: Monday is 0.
    weekend: frozenset[int]
    closes_on_holidays: bool
    revision: int
    # Every version of each day's entry, oldest first.
    entries: Mapping[date, tuple[Entry, ...]]

    def entry_at(self, day: date, moment: datetime) -> Entry | None:
        """The version of ``day``'s entry recorded at ``moment``, if there was one."""
        return next((e for e in self.entries.get(day, ()) if e.recorded_at(moment)), None)

    def years_recorded_at(self, moment: datetime) -> tuple[int, int] | None:
        """The first and last year with an entry recorded at ``moment``."""
        years = [
            day.year
            for day, versions in self.entries.items()
            if any(version.recorded_at(moment) for version in versions)
        ]
        return (min(years), max(years)) if years else None


@dataclass(frozen=True, slots=True)
class Release:
    id: str
    as_of: datetime
    calendars: Mapping[str, Calendar]

    def calendar(self, calendar_id: str) -> Calendar:
        try:
            return self.calendars[calendar_id]
        except KeyError:
            raise UnknownCalendarError(self.id, calendar_id) from None

    @property
    def version(self) -> tuple[int, ...]:
        return release_version(self.id)


def release_version(release_id: str) -> tuple[int, ...]:
    """``2026.10.28.1`` as a tuple, so releases compare in order."""
    try:
        return tuple(int(part) for part in release_id.split("."))
    except ValueError as error:
        raise ReleaseError(f"Not a release ID: {release_id!r}") from error


def load_release(path: Path) -> Release:
    return parse_release(path.read_bytes())


def parse_release(content: bytes) -> Release:
    try:
        document = json.loads(content)
        if document["schema_version"] not in SCHEMA_VERSIONS:
            raise ReleaseError(f"Unsupported schema_version {document['schema_version']!r}")
        calendars = {raw["id"]: _calendar(raw) for raw in document["calendars"]}
        return Release(
            id=str(document["release"]),
            as_of=_instant(document["as_of"]),
            calendars=calendars,
        )
    except (KeyError, TypeError, ValueError) as error:
        if isinstance(error, ReleaseError):
            raise
        raise ReleaseError(f"Malformed release: {error}") from error


def _calendar(raw: object) -> Calendar:
    if not isinstance(raw, dict):
        raise ReleaseError("A calendar must be an object")
    items, weekend = raw["entries"], raw["weekend"]
    if not isinstance(items, list) or not isinstance(weekend, list):
        raise ReleaseError(f"Calendar {raw.get('id')!r} needs entries and weekend lists")
    versions: dict[date, list[Entry]] = {}
    for item in items:
        entry = _entry(item)
        versions.setdefault(entry.day, []).append(entry)
    return Calendar(
        id=str(raw["id"]),
        name=str(raw["name"]),
        timezone=str(raw["timezone"]),
        weekend=frozenset(WEEKDAYS.index(str(day)) for day in weekend),
        closes_on_holidays=bool(raw["closes_on_holidays"]),
        revision=int(str(raw["revision"])),
        entries={
            day: tuple(sorted(found, key=lambda entry: entry.recorded_from))
            for day, found in versions.items()
        },
    )


def _entry(item: dict[str, object]) -> Entry:
    recorded_to = item.get("recorded_to")
    sources = item["sources"]
    if not isinstance(sources, list):
        raise ReleaseError("An entry's sources must be a list")
    return Entry(
        day=date.fromisoformat(str(item["date"])),
        is_open=bool(item["is_open"]),
        reason=str(item["reason"]),
        basis=Basis(str(item["basis"])),
        revision=int(str(item["revision"])),
        recorded_from=_instant(item["recorded_from"]),
        recorded_to=None if recorded_to is None else _instant(recorded_to),
        sources=tuple(
            SourceRef(
                source_id=str(source["source_id"]),
                # Schema version 1 had no publisher; the source ID is the best stand-in.
                publisher=str(source.get("publisher", source["source_id"])),
                url=str(source["url"]),
                sha256=str(source["sha256"]),
                fetched_at=_instant(source["fetched_at"]),
            )
            for source in sources
        ),
    )


def _instant(value: object) -> datetime:
    moment = datetime.fromisoformat(str(value))
    if moment.tzinfo is None:
        raise ReleaseError(f"Timestamp without a time zone: {value!r}")
    return moment
