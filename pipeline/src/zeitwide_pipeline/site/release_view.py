"""What the site files say about a release, read from its exported JSON document.

A release holds every entry version; the site files describe what was current at the
release's ``as_of``: versions not yet replaced (``recorded_to`` is null).
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime

WEEKDAYS = ("Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday")
MONTHS = (
    "January",
    "February",
    "March",
    "April",
    "May",
    "June",
    "July",
    "August",
    "September",
    "October",
    "November",
    "December",
)


@dataclass(frozen=True, slots=True)
class Closure:
    date: date
    reason: str
    basis: str
    source_urls: tuple[str, ...]

    @property
    def weekday(self) -> str:
        return WEEKDAYS[self.date.weekday()]


@dataclass(frozen=True, slots=True)
class DayEntry:
    """A current entry: a closure, or an opening on a day the weekend rule would close."""

    date: date
    is_open: bool
    reason: str
    basis: str
    source_urls: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class CalendarView:
    id: str
    name: str
    kind: str
    timezone: str
    weekend: tuple[str, ...]
    closes_on_holidays: bool
    revision: int
    # When this calendar last changed: its latest recorded version, or None with no entries.
    revised_at: datetime | None
    closures: tuple[Closure, ...]
    # Years with any current entry; the API's coverage for calendars that close on holidays.
    entry_years: tuple[int, ...]
    # Every current entry in date order: the closures plus any openings.
    entries: tuple[DayEntry, ...] = ()

    def covered_years(self, open_years: Sequence[int]) -> list[int]:
        """The years the calendar answers for; ``open_years`` for one that never closes."""
        if not self.closes_on_holidays:
            return list(open_years)
        if not self.entry_years:
            return []
        return list(range(min(self.entry_years), max(self.entry_years) + 1))

    def closures_in(self, year: int) -> list[Closure]:
        return [closure for closure in self.closures if closure.date.year == year]

    def closures_after(self, day: date) -> list[Closure]:
        return [closure for closure in self.closures if closure.date > day]

    def is_weekend(self, day: date) -> bool:
        return WEEKDAYS[day.weekday()].lower() in self.weekend


@dataclass(frozen=True, slots=True)
class ReleaseView:
    id: str
    as_of: datetime
    calendars: tuple[CalendarView, ...]

    @property
    def today(self) -> date:
        """The release's own date: site files depend on the data, never on when they're built."""
        return self.as_of.astimezone(UTC).date()

    @property
    def build_years(self) -> list[int]:
        """The years with compare pages: the release's year and the next."""
        return [self.today.year, self.today.year + 1]

    def calendar(self, calendar_id: str) -> CalendarView:
        for calendar in self.calendars:
            if calendar.id == calendar_id:
                return calendar
        raise KeyError(calendar_id)


def long_date(day: date) -> str:
    """Friday 25 December 2026, as the web pages write it."""
    return f"{WEEKDAYS[day.weekday()]} {day.day} {MONTHS[day.month - 1]} {day.year}"


def medium_date(day: date) -> str:
    """Fri 25 Dec 2026."""
    return f"{WEEKDAYS[day.weekday()][:3]} {day.day} {MONTHS[day.month - 1][:3]} {day.year}"


def read_release(document: Mapping[str, object]) -> ReleaseView:
    calendars = document.get("calendars")
    if not isinstance(calendars, list):
        raise TypeError("A release has a list of calendars")
    return ReleaseView(
        id=_text(document, "release"),
        as_of=_instant(_text(document, "as_of")),
        calendars=tuple(_calendar(calendar) for calendar in calendars),
    )


def _calendar(raw: object) -> CalendarView:
    if not isinstance(raw, dict):
        raise TypeError("A calendar is an object")
    entries = [entry for entry in raw.get("entries", []) if isinstance(entry, dict)]
    current = [entry for entry in entries if entry.get("recorded_to") is None]
    changes = [_instant(str(entry["recorded_from"])) for entry in entries] + [
        _instant(str(entry["recorded_to"])) for entry in entries if entry.get("recorded_to")
    ]
    day_entries = sorted(
        (
            DayEntry(
                date=date.fromisoformat(str(entry["date"])),
                is_open=entry.get("is_open") is True,
                reason=str(entry["reason"]),
                basis=str(entry["basis"]),
                source_urls=tuple(
                    dict.fromkeys(
                        str(source["url"])
                        for source in entry.get("sources", [])
                        if isinstance(source, dict)
                    )
                ),
            )
            for entry in current
        ),
        key=lambda entry: entry.date,
    )
    closures = [
        Closure(entry.date, entry.reason, entry.basis, entry.source_urls)
        for entry in day_entries
        if not entry.is_open
    ]
    return CalendarView(
        id=_text(raw, "id"),
        name=_text(raw, "name"),
        kind=_text(raw, "kind"),
        timezone=_text(raw, "timezone"),
        weekend=tuple(str(day) for day in raw.get("weekend", [])),
        closes_on_holidays=bool(raw.get("closes_on_holidays")),
        revision=int(raw.get("revision", 0)),
        revised_at=max(changes, default=None),
        closures=tuple(closures),
        entries=tuple(day_entries),
        entry_years=tuple(sorted({date.fromisoformat(str(e["date"])).year for e in current})),
    )


def _text(raw: Mapping[str, object], key: str) -> str:
    value = raw.get(key)
    if not isinstance(value, str) or not value:
        raise TypeError(f"Expected text at {key!r}")
    return value


def _instant(text: str) -> datetime:
    parsed = datetime.fromisoformat(text)
    if parsed.tzinfo is None:
        raise ValueError(f"Instant without a time zone: {text}")
    return parsed.astimezone(UTC)
