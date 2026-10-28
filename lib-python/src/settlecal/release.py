"""Reading a SettleCal dataset release (``dataset/schema/release.schema.json``)."""

import json
from dataclasses import dataclass
from datetime import date
from pathlib import Path

WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")


class ReleaseError(ValueError):
    """The release file isn't one this version of the library can read."""


@dataclass(frozen=True, slots=True)
class Entry:
    """A day that differs from the calendar's weekend rule: a closure, or an extra open day."""

    day: date
    is_open: bool
    reason: str
    basis: str


@dataclass(frozen=True, slots=True)
class Calendar:
    id: str
    name: str
    timezone: str
    weekend: frozenset[int]
    revision: int
    entries: dict[date, Entry]

    def is_open(self, day: date) -> bool:
        """An entry wins; otherwise a day is open unless it falls on the weekend."""
        entry = self.entries.get(day)
        if entry is not None:
            return entry.is_open
        return day.weekday() not in self.weekend


@dataclass(frozen=True, slots=True)
class Release:
    id: str
    calendars: dict[str, Calendar]

    def calendar(self, calendar_id: str) -> Calendar:
        try:
            return self.calendars[calendar_id]
        except KeyError:
            raise ReleaseError(f"Release {self.id} has no calendar {calendar_id!r}") from None

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
        if document["schema_version"] != 1:
            raise ReleaseError(f"Unsupported schema_version {document['schema_version']!r}")
        calendars = {raw["id"]: _calendar(raw) for raw in document["calendars"]}
        return Release(id=str(document["release"]), calendars=calendars)
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
    entries = {}
    for item in items:
        day = date.fromisoformat(str(item["date"]))
        entries[day] = Entry(day, bool(item["is_open"]), str(item["reason"]), str(item["basis"]))
    return Calendar(
        id=str(raw["id"]),
        name=str(raw["name"]),
        timezone=str(raw["timezone"]),
        weekend=frozenset(WEEKDAYS.index(str(day)) for day in weekend),
        revision=int(str(raw["revision"])),
        entries=entries,
    )
