"""Open Zeitwide library: business-day answers with their evidence, offline or online.

>>> from datetime import date
>>> from zeitwide import ZeitWide
>>> ZeitWide().is_business_day("US-FEDWIRE", date(2026, 11, 26)).is_business_day
False
"""

from importlib.metadata import version

from zeitwide import webhooks
from zeitwide.answers import (
    CalendarDay,
    Closure,
    ClosureList,
    DayAnswer,
    DecidedBy,
    Receipt,
    ShiftAnswer,
    SkippedDay,
    Source,
)
from zeitwide.client import ApiError
from zeitwide.core import ZeitWide
from zeitwide.engine import Combine, OutOfRangeError
from zeitwide.receipts import KeyRing, Verification, verify_receipt
from zeitwide.release import (
    Basis,
    Release,
    ReleaseError,
    UnknownCalendarError,
    load_release,
    parse_release,
)

__version__ = version("zeitwide")

__all__ = [
    "ApiError",
    "Basis",
    "CalendarDay",
    "Closure",
    "ClosureList",
    "Combine",
    "DayAnswer",
    "DecidedBy",
    "KeyRing",
    "OutOfRangeError",
    "Receipt",
    "Release",
    "ReleaseError",
    "ShiftAnswer",
    "SkippedDay",
    "Source",
    "UnknownCalendarError",
    "Verification",
    "ZeitWide",
    "__version__",
    "load_release",
    "parse_release",
    "verify_receipt",
    "webhooks",
]
