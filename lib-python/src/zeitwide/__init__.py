"""Open Zeitwide library: business-day answers from the bundled dataset."""

from importlib.metadata import version

from zeitwide.engine import (
    Combine,
    OutOfRangeError,
    Shifted,
    add_business_days,
    is_business_day,
    next_business_day,
)
from zeitwide.release import (
    Basis,
    Calendar,
    Entry,
    Release,
    ReleaseError,
    SourceRef,
    UnknownCalendarError,
    load_release,
    parse_release,
)

__version__ = version("zeitwide")

__all__ = [
    "Basis",
    "Calendar",
    "Combine",
    "Entry",
    "OutOfRangeError",
    "Release",
    "ReleaseError",
    "Shifted",
    "SourceRef",
    "UnknownCalendarError",
    "__version__",
    "add_business_days",
    "is_business_day",
    "load_release",
    "next_business_day",
    "parse_release",
]
