"""Calendar definitions: weekend, observance and operating-hours rules, as data.

Each calendar is one JSON file in ``catalog/``. The pipeline keeps the ``calendar`` table
in step with them, and the release builder exports them with the dataset, so Java and
Python read the same rules.
"""

from zeitwide_pipeline.calendars.catalog import load_catalog
from zeitwide_pipeline.calendars.model import (
    CalendarDefinition,
    CalendarKind,
    Cutoff,
    Observance,
    OperatingHours,
    SaturdayRule,
    SundayRule,
    Weekday,
    observed_date,
    operator_calendars,
)

__all__ = [
    "CalendarDefinition",
    "CalendarKind",
    "Cutoff",
    "Observance",
    "OperatingHours",
    "SaturdayRule",
    "SundayRule",
    "Weekday",
    "load_catalog",
    "observed_date",
    "operator_calendars",
]
