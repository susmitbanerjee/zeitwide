"""The shape of a calendar definition, validated where the JSON files are read."""

import re
from collections.abc import Mapping
from datetime import date, time, timedelta
from enum import StrEnum
from typing import Annotated, Literal, Self
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import BaseModel, ConfigDict, Field, HttpUrl, field_validator, model_validator

CALENDAR_ID_PATTERN = re.compile(r"^[A-Z0-9]+(-[A-Z0-9]+)*$")
MAX_TEXT = 500
SATURDAY = 5
SUNDAY = 6


class Weekday(StrEnum):
    MONDAY = "monday"
    TUESDAY = "tuesday"
    WEDNESDAY = "wednesday"
    THURSDAY = "thursday"
    FRIDAY = "friday"
    SATURDAY = "saturday"
    SUNDAY = "sunday"


class CalendarKind(StrEnum):
    RAIL = "rail"
    PUBLIC = "public"
    EXCHANGE = "exchange"


class SaturdayRule(StrEnum):
    """What happens when a holiday falls on a Saturday."""

    # No weekday is closed instead (the Federal Reserve Banks open the Friday before).
    NONE = "none"
    PRECEDING_FRIDAY = "preceding_friday"


class SundayRule(StrEnum):
    """What happens when a holiday falls on a Sunday."""

    NONE = "none"
    FOLLOWING_MONDAY = "following_monday"


class _Strict(BaseModel):
    model_config = ConfigDict(frozen=True, extra="forbid")


type Text = Annotated[str, Field(min_length=1, max_length=MAX_TEXT)]


class Observance(_Strict):
    """How a holiday that falls on a weekend moves to a weekday.

    Only for sources that publish the holiday's nominal date; sources that publish the
    observed date already applied it.
    """

    saturday: SaturdayRule
    sunday: SundayRule
    note: Text


class Cutoff(_Strict):
    """One deadline in the operating day, in the calendar's time zone."""

    name: Text
    deadline: time
    # When value submitted by the deadline settles, if the operator states it.
    settles_at: time | None = None
    settles_on: Literal["same_day", "next_business_day"] | None = None


class OperatingHours(_Strict):
    always_open: bool = False
    opens: time | None = None
    # True when the business day starts the evening before (Fedwire opens 9 p.m. ET).
    opens_previous_day: bool = False
    closes: time | None = None
    cutoffs: tuple[Cutoff, ...] = ()
    note: Text | None = None

    @model_validator(mode="after")
    def _hours_or_always_open(self) -> Self:
        if self.always_open and (self.opens or self.closes or self.cutoffs):
            raise ValueError("An always-open calendar has no opening, closing or cutoff times")
        return self


class Provenance(_Strict):
    url: HttpUrl
    retrieved: date
    states: Text


class CalendarDefinition(_Strict):
    id: str = Field(max_length=64)
    name: Text
    kind: CalendarKind
    timezone: str
    weekend: tuple[Weekday, ...]
    # Calendars that close on no holiday at all, such as FedNow.
    closes_on_holidays: bool = True
    observance: Observance | None = None
    operating_hours: OperatingHours | None = None
    # Sources run by the calendar's own operator. A date one of them lists is
    # basis=published; a date only other sources list (or the rules imply) is projected.
    operator_sources: tuple[str, ...] = ()
    provenance: tuple[Provenance, ...] = Field(min_length=1)

    @field_validator("id")
    @classmethod
    def _id_format(cls, value: str) -> str:
        if not CALENDAR_ID_PATTERN.match(value):
            raise ValueError("A calendar ID is uppercase letters, digits and hyphens")
        return value

    @field_validator("timezone")
    @classmethod
    def _known_zone(cls, value: str) -> str:
        try:
            ZoneInfo(value)
        except (ZoneInfoNotFoundError, ValueError) as error:
            raise ValueError(f"Unknown time zone {value!r}") from error
        return value

    def weekend_rule(self) -> dict[str, object]:
        """The ``calendar.weekend_rule`` column."""
        return {"days": [day.value for day in self.weekend]}


def observed_date(holiday: date, observance: Observance) -> date:
    """The weekday a holiday is observed on; the date itself when no rule moves it."""
    weekday = holiday.weekday()
    if weekday == SATURDAY and observance.saturday is SaturdayRule.PRECEDING_FRIDAY:
        return holiday - timedelta(days=1)
    if weekday == SUNDAY and observance.sunday is SundayRule.FOLLOWING_MONDAY:
        return holiday + timedelta(days=1)
    return holiday


def operator_calendars(
    source_id: str, calendars: Mapping[str, CalendarDefinition]
) -> frozenset[str]:
    """The calendars whose operator runs ``source_id``."""
    return frozenset(c.id for c in calendars.values() if source_id in c.operator_sources)
