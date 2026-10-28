"""Open SettleCal library: business-day answers from the bundled dataset."""

from importlib.metadata import version

from settlecal.engine import Combine, Shifted, add_business_days, is_business_day
from settlecal.release import Calendar, Entry, Release, ReleaseError, load_release, parse_release

__version__ = version("settlecal")

__all__ = [
    "Calendar",
    "Combine",
    "Entry",
    "Release",
    "ReleaseError",
    "Shifted",
    "__version__",
    "add_business_days",
    "is_business_day",
    "load_release",
    "parse_release",
]
