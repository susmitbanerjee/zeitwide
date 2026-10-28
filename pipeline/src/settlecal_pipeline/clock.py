"""The one place that reads the wall clock; everything else takes a Clock."""

from collections.abc import Callable
from datetime import UTC, datetime

type Clock = Callable[[], datetime]


def utc_now() -> datetime:
    return datetime.now(UTC)
