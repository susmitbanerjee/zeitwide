import json
from datetime import date, timedelta

import pytest
from hypothesis import given
from hypothesis import strategies as st

from settlecal import (
    Combine,
    ReleaseError,
    add_business_days,
    is_business_day,
    parse_release,
)
from settlecal.conformance import run_case, run_cases
from settlecal.release import release_version

THANKSGIVING = date(2026, 11, 26)


def release_bytes(release_id: str = "2026.10.28.1") -> bytes:
    def calendar(
        calendar_id: str, weekend: list[str], entries: list[tuple[str, bool]]
    ) -> dict[str, object]:
        return {
            "id": calendar_id,
            "name": calendar_id,
            "kind": "rail",
            "timezone": "America/New_York",
            "weekend": weekend,
            "closes_on_holidays": bool(weekend),
            "observance": None,
            "operating_hours": None,
            "revision": 1,
            "entries": [
                {
                    "date": day,
                    "is_open": is_open,
                    "reason": "Holiday",
                    "basis": "published",
                    "revision": 1,
                    "recorded_from": "2026-01-01T00:00:00+00:00",
                    "sources": [],
                }
                for day, is_open in entries
            ],
        }

    return json.dumps(
        {
            "schema_version": 1,
            "release": release_id,
            "as_of": "2026-10-28T00:00:00+00:00",
            "license": "CC BY-NC 4.0",
            "calendars": [
                calendar("US-FEDWIRE", ["saturday", "sunday"], [("2026-11-26", False)]),
                calendar("US-FEDNOW", [], []),
                calendar("EU-T2", ["saturday", "sunday"], [("2026-04-03", False)]),
            ],
        }
    ).encode()


RELEASE = parse_release(release_bytes())


def test_entry_closes_a_weekday() -> None:
    assert not is_business_day(RELEASE, ["US-FEDWIRE"], THANKSGIVING)


def test_weekend_is_closed_unless_the_calendar_has_none() -> None:
    saturday = date(2026, 11, 28)

    assert not is_business_day(RELEASE, ["US-FEDWIRE"], saturday)
    assert is_business_day(RELEASE, ["US-FEDNOW"], saturday)


def test_calendars_combine() -> None:
    good_friday = date(2026, 4, 3)

    assert not is_business_day(RELEASE, ["US-FEDWIRE", "EU-T2"], good_friday, Combine.ALL_OPEN)
    assert is_business_day(RELEASE, ["US-FEDWIRE", "EU-T2"], good_friday, Combine.ANY_OPEN)


def test_adding_business_days_reports_what_it_skipped() -> None:
    shifted = add_business_days(RELEASE, ["US-FEDWIRE"], date(2026, 11, 25), 2)

    assert (shifted.date, shifted.skipped) == (
        date(2026, 11, 30),
        (THANKSGIVING, date(2026, 11, 28), date(2026, 11, 29)),
    )


def test_subtracting_business_days_walks_backwards() -> None:
    shifted = add_business_days(RELEASE, ["US-FEDWIRE"], date(2026, 11, 30), -1)

    assert shifted.date == date(2026, 11, 27)


def test_adding_zero_returns_the_day() -> None:
    assert add_business_days(RELEASE, ["US-FEDWIRE"], THANKSGIVING, 0).date == THANKSGIVING


def test_no_calendars_is_an_error() -> None:
    with pytest.raises(ValueError, match="At least one"):
        is_business_day(RELEASE, [], THANKSGIVING)


def test_unknown_calendar_is_an_error() -> None:
    with pytest.raises(ReleaseError, match="no calendar"):
        is_business_day(RELEASE, ["XX-NONE"], THANKSGIVING)


def test_malformed_release_is_rejected() -> None:
    with pytest.raises(ReleaseError):
        parse_release(b'{"schema_version": 2}')


@given(st.dates(date(2026, 1, 1), date(2027, 12, 31)), st.integers(min_value=1, max_value=40))
def test_added_days_land_on_a_business_day_after_the_start(start: date, days: int) -> None:
    shifted = add_business_days(RELEASE, ["US-FEDWIRE"], start, days)

    assert shifted.date > start
    assert is_business_day(RELEASE, ["US-FEDWIRE"], shifted.date)
    assert all(not is_business_day(RELEASE, ["US-FEDWIRE"], day) for day in shifted.skipped)
    assert (shifted.date - start).days == days + len(shifted.skipped)


@given(st.dates(date(2026, 1, 1), date(2027, 12, 31)), st.integers(min_value=1, max_value=40))
def test_adding_then_subtracting_returns_on_or_before_the_start(start: date, days: int) -> None:
    there = add_business_days(RELEASE, ["US-FEDWIRE"], start, days).date
    back = add_business_days(RELEASE, ["US-FEDWIRE"], there, -days).date

    assert back <= start
    assert back > start - timedelta(days=days * 3)


def test_case_passes_when_the_answer_matches() -> None:
    case = {
        "id": "thanksgiving",
        "dataset_release": "2026.10.28.1",
        "input": {
            "operation": "is_business_day",
            "calendars": ["US-FEDWIRE"],
            "date": "2026-11-26",
        },
        "expected": {"is_business_day": False},
    }

    assert run_case(RELEASE, case).passed


def test_case_fails_with_the_difference() -> None:
    case = {
        "id": "wrong",
        "dataset_release": "2026.10.28.1",
        "input": {
            "operation": "add_business_days",
            "calendars": ["US-FEDWIRE"],
            "date": "2026-11-25",
            "days": 1,
        },
        "expected": {"date": "2026-11-26", "skipped": []},
    }

    result = run_case(RELEASE, case)

    assert not result.passed
    assert "2026-11-27" in result.detail


def test_cases_for_later_releases_do_not_apply() -> None:
    case = {"id": "later", "dataset_release": "2027.01.01.1", "input": {}, "expected": {}}

    assert run_cases(RELEASE, [case]) == []
    assert release_version("2026.10.28.10") > release_version("2026.10.28.9")


def test_as_of_cases_need_the_api() -> None:
    case = {
        "id": "as-of",
        "dataset_release": "2026.10.28.1",
        "input": {"as_of": "2026-01-01T00:00:00Z"},
        "expected": {},
    }

    assert not run_case(RELEASE, case).passed
