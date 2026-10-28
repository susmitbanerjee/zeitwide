"""Business-day answers from a release. The rules are the release's data, never this code."""

from dataclasses import dataclass
from datetime import date, timedelta
from enum import StrEnum

from settlecal.release import Release

ONE_DAY = timedelta(days=1)
# Shifted has a field named ``date``, which would hide the type inside the class.
type Day = date
# A search longer than this means the data, not the question, is wrong.
MAX_DAYS_SEARCHED = 3660


class Combine(StrEnum):
    """How several calendars combine into one answer."""

    ALL_OPEN = "all_open"
    ANY_OPEN = "any_open"


@dataclass(frozen=True, slots=True)
class Shifted:
    date: Day
    # Days passed over because they weren't business days, in the order they were met.
    skipped: tuple[Day, ...]


def is_business_day(
    release: Release,
    calendar_ids: list[str],
    day: date,
    combine: Combine = Combine.ALL_OPEN,
) -> bool:
    if not calendar_ids:
        raise ValueError("At least one calendar is needed")
    answers = [release.calendar(calendar_id).is_open(day) for calendar_id in calendar_ids]
    return all(answers) if combine is Combine.ALL_OPEN else any(answers)


def add_business_days(
    release: Release,
    calendar_ids: list[str],
    day: date,
    days: int,
    combine: Combine = Combine.ALL_OPEN,
) -> Shifted:
    """Moves ``days`` business days from ``day`` (backwards when negative).

    The start day doesn't count, so adding 1 to a Friday before a normal weekend gives
    the Monday and skips Saturday and Sunday. Adding 0 returns ``day`` itself.
    """
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
        if is_business_day(release, calendar_ids, current, combine):
            remaining -= 1
        else:
            skipped.append(current)
    return Shifted(current, tuple(skipped))
