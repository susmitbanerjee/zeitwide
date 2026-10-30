"""When a source is due for its next check. Pure functions."""

from datetime import datetime, timedelta

SECONDS_PER_MINUTE = 60
SECONDS_PER_HOUR = 60 * SECONDS_PER_MINUTE
SECONDS_PER_DAY = 24 * SECONDS_PER_HOUR


def is_due(last_checked_at: datetime | None, every: timedelta, now: datetime) -> bool:
    return last_checked_at is None or last_checked_at + every <= now


def iso_duration(every: timedelta) -> str:
    """ISO 8601 form for the ``source.schedule`` column, such as ``P1D`` or ``PT15M``."""
    total = int(every.total_seconds())
    if total <= 0 or every != timedelta(seconds=total):
        raise ValueError("A schedule is a positive whole number of seconds")
    days, rest = divmod(total, SECONDS_PER_DAY)
    hours, rest = divmod(rest, SECONDS_PER_HOUR)
    minutes, seconds = divmod(rest, SECONDS_PER_MINUTE)
    time_part = "".join(
        f"{value}{unit}" for value, unit in ((hours, "H"), (minutes, "M"), (seconds, "S")) if value
    )
    return "P" + (f"{days}D" if days else "") + (f"T{time_part}" if time_part else "")
