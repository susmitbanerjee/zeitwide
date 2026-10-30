"""GOV.UK bank holidays for England and Wales, which are also CHAPS closing days.

The Bank of England states that CHAPS is closed on bank and public holidays in England
and Wales (see GB-CHAPS in the calendar catalog), so each date closes both calendars.
"""

import json
from dataclasses import dataclass
from datetime import date

from pydantic import BaseModel, ConfigDict, Field, ValidationError

from zeitwide_pipeline.models import Observation, SnapshotMeta, SourceRole
from zeitwide_pipeline.sources.base import ParseError, SourceDefinition
from zeitwide_pipeline.sources.html_tables import clean

DIVISION = "england-and-wales"
CALENDAR_IDS = ("GB-EAW", "GB-CHAPS")
MIN_EVENTS = 8


class _Event(BaseModel):
    model_config = ConfigDict(frozen=True)

    title: str = Field(min_length=1, max_length=200)
    date: date
    notes: str = Field(default="", max_length=200)


class _Division(BaseModel):
    model_config = ConfigDict(frozen=True)

    division: str
    events: list[_Event]


@dataclass(frozen=True, slots=True)
class GovUkBankHolidayParser:
    name: str = "govuk_bank_holidays_json"
    version: str = "1"

    def parse(self, content: bytes, meta: SnapshotMeta) -> list[Observation]:
        try:
            divisions = json.loads(content)
            division = _Division.model_validate(divisions[DIVISION])
        except (ValueError, KeyError, TypeError, ValidationError) as error:
            raise ParseError(f"No valid {DIVISION!r} division in {meta.url}") from error
        if division.division != DIVISION or len(division.events) < MIN_EVENTS:
            raise ParseError(f"Unexpected {DIVISION!r} division in {meta.url}")
        return [
            Observation(calendar_id, event.date, False, _reason(event))
            for event in division.events
            for calendar_id in CALENDAR_IDS
        ]


def _reason(event: _Event) -> str:
    title = clean(event.title)
    return f"{title} ({clean(event.notes)})" if event.notes else title


SOURCE = SourceDefinition(
    id="govuk-bank-holidays",
    publisher="GOV.UK (Government Digital Service)",
    url="https://www.gov.uk/bank-holidays.json",
    parser=GovUkBankHolidayParser(),
    content_types=frozenset({"application/json"}),
    role=SourceRole.PRIMARY,
)
