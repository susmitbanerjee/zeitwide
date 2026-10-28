from datetime import date, time

import pytest
from pydantic import ValidationError

from settlecal_pipeline.calendars import (
    CalendarDefinition,
    Observance,
    OperatingHours,
    SaturdayRule,
    SundayRule,
    Weekday,
    load_catalog,
    observed_date,
)

FED = Observance(saturday=SaturdayRule.NONE, sunday=SundayRule.FOLLOWING_MONDAY, note="Fed")
FEDERAL = Observance(
    saturday=SaturdayRule.PRECEDING_FRIDAY, sunday=SundayRule.FOLLOWING_MONDAY, note="OPM"
)
SATURDAY_JULY_4 = date(2026, 7, 4)
SUNDAY_JULY_4 = date(2027, 7, 4)


def test_catalog_defines_every_calendar() -> None:
    assert sorted(load_catalog()) == [
        "DE",
        "EU-T2",
        "GB-CHAPS",
        "GB-EAW",
        "US-FEDACH",
        "US-FEDERAL",
        "US-FEDNOW",
        "US-FEDWIRE",
    ]


def test_fednow_is_open_every_day_of_the_year() -> None:
    fednow = load_catalog()["US-FEDNOW"]

    assert (fednow.weekend, fednow.closes_on_holidays) == ((), False)
    assert fednow.operating_hours is not None
    assert fednow.operating_hours.always_open


@pytest.mark.parametrize("calendar_id", ["US-FEDWIRE", "US-FEDACH"])
def test_fed_rails_follow_the_reserve_banks_observance(calendar_id: str) -> None:
    calendar = load_catalog()[calendar_id]

    assert calendar.weekend == (Weekday.SATURDAY, Weekday.SUNDAY)
    assert calendar.observance is not None
    assert (calendar.observance.saturday, calendar.observance.sunday) == (
        SaturdayRule.NONE,
        SundayRule.FOLLOWING_MONDAY,
    )


def test_fedach_cutoffs_are_operating_hours_data() -> None:
    hours = load_catalog()["US-FEDACH"].operating_hours

    assert hours is not None
    same_day = [c for c in hours.cutoffs if c.settles_on == "same_day"]
    assert [(c.deadline, c.settles_at) for c in same_day] == [
        (time(10, 30), time(13, 0)),
        (time(14, 45), time(17, 0)),
        (time(16, 45), time(18, 0)),
    ]


def test_fedwire_day_opens_the_evening_before() -> None:
    hours = load_catalog()["US-FEDWIRE"].operating_hours

    assert hours is not None
    assert (hours.opens, hours.opens_previous_day, hours.closes) == (time(21), True, time(19))


@pytest.mark.parametrize(
    ("observance", "holiday", "expected"),
    [
        (FED, SATURDAY_JULY_4, SATURDAY_JULY_4),
        (FED, SUNDAY_JULY_4, date(2027, 7, 5)),
        (FEDERAL, SATURDAY_JULY_4, date(2026, 7, 3)),
        (FEDERAL, SUNDAY_JULY_4, date(2027, 7, 5)),
        (FED, date(2026, 11, 26), date(2026, 11, 26)),
    ],
)
def test_observed_date(observance: Observance, holiday: date, expected: date) -> None:
    assert observed_date(holiday, observance) == expected


def test_always_open_calendar_has_no_cutoffs() -> None:
    with pytest.raises(ValidationError, match="always-open"):
        OperatingHours(always_open=True, closes=time(18))


def test_unknown_time_zone_is_rejected() -> None:
    definition = load_catalog()["GB-EAW"].model_dump()

    with pytest.raises(ValidationError, match="Unknown time zone"):
        CalendarDefinition.model_validate({**definition, "timezone": "Mars/Olympus"})


def test_malformed_id_is_rejected() -> None:
    definition = load_catalog()["GB-EAW"].model_dump()

    with pytest.raises(ValidationError, match="uppercase"):
        CalendarDefinition.model_validate({**definition, "id": "gb-eaw"})


def test_weekend_rule_column() -> None:
    assert load_catalog()["GB-CHAPS"].weekend_rule() == {"days": ["saturday", "sunday"]}
