"""Business-day answers from a release. The rules are the release's data, never this code.

The contract is ``dataset/README.md``; the Java engine in ``api/`` implements the same one,
and the conformance suite checks both. Pure: no I/O, no clock.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from enum import StrEnum

from settlecal.release import Calendar, Release

ONE_DAY = timedelta(days=1)
# Shifted has a field named ``date``, which would hide the type inside the class.
type Day = date
# A search longer than this means the data, not the question, is wrong.
MAX_DAYS_SEARCHED = 3660


class Combine(StrEnum):
    """How several calendars combine into one answer."""

    ALL_OPEN = "all_open"
    ANY_OPEN = "any_open"


class OutOfRangeError(ValueError):
    """The date is outside what the calendar's sources had published: never guessed."""

    def __init__(self, calendar_id: str, day: date) -> None:
        super().__init__(f"{calendar_id} has no source coverage for {day.isoformat()}")
        self.calendar_id = calendar_id
        self.day = day


@dataclass(frozen=True, slots=True)
class Shifted:
    date: Day
    # Days passed over because they weren't business days, in the order they were met.
    skipped: tuple[Day, ...]


@dataclass(frozen=True, slots=True)
class _Covered:
    calendar: Calendar
    # None when the calendar closes on no holiday: its weekend rule answers any date.
    first: date | None
    last: date | None

    def check(self, day: date) -> None:
        if self.first is None or self.last is None:
            return
        if not self.first <= day <= self.last:
            raise OutOfRangeError(self.calendar.id, day)


@dataclass(frozen=True, slots=True)
class _View:
    """The calendars of one question, as recorded at one moment."""

    calendars: tuple[_Covered, ...]
    moment: datetime
    combine: Combine

    def is_business_day(self, day: date) -> bool:
        # Checked for every calendar first: any_open never answers past a gap.
        for covered in self.calendars:
            covered.check(day)
        answers = [self._is_open(covered.calendar, day) for covered in self.calendars]
        return all(answers) if self.combine is Combine.ALL_OPEN else any(answers)

    def _is_open(self, calendar: Calendar, day: date) -> bool:
        entry = calendar.entry_at(day, self.moment)
        if entry is not None:
            return entry.is_open
        return day.weekday() not in calendar.weekend


def is_business_day(
    release: Release,
    calendar_ids: Sequence[str],
    day: date,
    combine: Combine = Combine.ALL_OPEN,
    *,
    as_of: datetime | None = None,
) -> bool:
    """Whether ``day`` is a business day, as SettleCal recorded it at ``as_of``.

    Args:
        release: The dataset release to answer from.
        calendar_ids: One or more calendars, combined with ``combine``.
        day: The date asked about.
        combine: ``all_open`` (every calendar is open) or ``any_open`` (at least one).
        as_of: When to answer as of; the release's own ``as_of`` when omitted.

    Raises:
        UnknownCalendarError: A calendar isn't in the release.
        OutOfRangeError: ``day`` is outside a calendar's source coverage.
    """
    return _view(release, calendar_ids, combine, as_of).is_business_day(day)


def next_business_day(
    release: Release,
    calendar_ids: Sequence[str],
    day: date,
    combine: Combine = Combine.ALL_OPEN,
    *,
    as_of: datetime | None = None,
) -> Shifted:
    """The first business day after ``day`` (never ``day`` itself), and the days skipped."""
    return add_business_days(release, calendar_ids, day, 1, combine, as_of=as_of)


def add_business_days(  # noqa: PLR0913 - the question has this many parts
    release: Release,
    calendar_ids: Sequence[str],
    day: date,
    days: int,
    combine: Combine = Combine.ALL_OPEN,
    *,
    as_of: datetime | None = None,
) -> Shifted:
    """Moves ``days`` business days from ``day`` (backwards when negative).

    The start day doesn't count, so adding 1 to a Friday before a normal weekend gives
    the Monday and skips Saturday and Sunday. Adding 0 returns ``day`` itself. Every date
    looked at, ``day`` included, must be within source coverage.
    """
    view = _view(release, calendar_ids, combine, as_of)
    for covered in view.calendars:
        covered.check(day)
    step = ONE_DAY if days >= 0 else -ONE_DAY
    remaining = abs(days)
    current = day
    skipped: list[date] = []
    searched = 0
    while remaining:
        current += step
        searched += 1
        if searched > MAX_DAYS_SEARCHED:
            raise ValueError(f"No business day within {MAX_DAYS_SEARCHED} days of {day}")
        if view.is_business_day(current):
            remaining -= 1
        else:
            skipped.append(current)
    return Shifted(current, tuple(skipped))


def _view(
    release: Release,
    calendar_ids: Sequence[str],
    combine: Combine,
    as_of: datetime | None,
) -> _View:
    if not calendar_ids:
        raise ValueError("At least one calendar is needed")
    if as_of is not None and as_of.tzinfo is None:
        raise ValueError("as_of needs a time zone")
    moment = release.as_of if as_of is None else as_of
    calendars = tuple(
        _covered(release.calendar(calendar_id), moment) for calendar_id in calendar_ids
    )
    return _View(calendars, moment, combine)


def _covered(calendar: Calendar, moment: datetime) -> _Covered:
    if not calendar.closes_on_holidays:
        return _Covered(calendar, None, None)
    years = calendar.years_recorded_at(moment)
    if years is None:
        # Nothing recorded yet: an empty range, so every date is out of range.
        return _Covered(calendar, date.max, date.min)
    return _Covered(calendar, date(years[0], 1, 1), date(years[1], 12, 31))
