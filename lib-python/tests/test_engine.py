import json
from collections.abc import Sequence
from datetime import UTC, date, datetime, timedelta

import pytest
from hypothesis import given
from hypothesis import strategies as st

from settlecal import (
    Basis,
    Combine,
    OutOfRangeError,
    ReleaseError,
    UnknownCalendarError,
    add_business_days,
    is_business_day,
    next_business_day,
    parse_release,
)
from settlecal.conformance import Outcome, run_case, run_cases
from settlecal.release import release_version

THANKSGIVING = date(2026, 11, 26)
RECORDED = "2026-01-01T00:00:00+00:00"
# A made-up correction, to test history: Thanksgiving first recorded as the 27th.
CORRECTED = "2026-03-01T00:00:00+00:00"


def entry(day: str, *, is_open: bool = False, **times: str | None) -> dict[str, object]:
    return {
        "date": day,
        "is_open": is_open,
        "reason": "Holiday",
        "basis": "published",
        "revision": 1,
        "recorded_from": times.get("recorded_from", RECORDED),
        "recorded_to": times.get("recorded_to"),
        "sources": [
            {
                "source_id": "test",
                "publisher": "Test publisher",
                "url": "https://example.com/holidays",
                "sha256": "0" * 64,
                "fetched_at": RECORDED,
            }
        ],
    }


def release_bytes(release_id: str = "2026.10.28.1", schema_version: int = 2) -> bytes:
    def calendar(calendar_id: str, weekend: list[str], entries: Sequence[object]) -> object:
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
            "entries": entries,
        }

    fedwire = [
        entry("2026-01-01"),
        entry("2026-11-27", recorded_to=CORRECTED),
        entry("2026-11-26", recorded_from=CORRECTED),
        entry("2027-12-24"),
    ]
    return json.dumps(
        {
            "schema_version": schema_version,
            "release": release_id,
            "as_of": "2026-10-28T00:00:00+00:00",
            "license": "CC BY-NC 4.0",
            "calendars": [
                calendar("US-FEDWIRE", ["saturday", "sunday"], fedwire),
                calendar("US-FEDNOW", [], []),
                calendar("EU-T2", ["saturday", "sunday"], [entry("2026-04-03")]),
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


def test_answers_use_the_version_recorded_at_as_of() -> None:
    before = datetime(2026, 2, 1, tzinfo=UTC)

    assert is_business_day(RELEASE, ["US-FEDWIRE"], THANKSGIVING, as_of=before)
    assert not is_business_day(RELEASE, ["US-FEDWIRE"], date(2026, 11, 27), as_of=before)
    assert is_business_day(RELEASE, ["US-FEDWIRE"], date(2026, 11, 27))


def test_a_replaced_version_ends_where_the_next_begins() -> None:
    corrected = datetime.fromisoformat(CORRECTED)

    assert not is_business_day(RELEASE, ["US-FEDWIRE"], THANKSGIVING, as_of=corrected)
    assert not is_business_day(
        RELEASE, ["US-FEDWIRE"], date(2026, 11, 27), as_of=corrected - timedelta(microseconds=1)
    )


def test_dates_outside_the_recorded_years_are_out_of_range() -> None:
    with pytest.raises(OutOfRangeError, match="US-FEDWIRE") as raised:
        is_business_day(RELEASE, ["US-FEDWIRE"], date(2028, 1, 3))

    assert raised.value.day == date(2028, 1, 3)
    assert is_business_day(RELEASE, ["US-FEDWIRE"], date(2027, 12, 31))
    with pytest.raises(OutOfRangeError):
        is_business_day(RELEASE, ["US-FEDWIRE"], date(2025, 12, 31))


def test_nothing_recorded_yet_is_out_of_range() -> None:
    with pytest.raises(OutOfRangeError):
        is_business_day(
            RELEASE, ["EU-T2"], date(2026, 4, 3), as_of=datetime(2025, 1, 1, tzinfo=UTC)
        )


def test_any_open_does_not_answer_past_a_gap() -> None:
    with pytest.raises(OutOfRangeError):
        is_business_day(RELEASE, ["US-FEDNOW", "US-FEDWIRE"], date(2099, 1, 1), Combine.ANY_OPEN)


def test_calendars_that_never_close_have_no_range() -> None:
    assert is_business_day(RELEASE, ["US-FEDNOW"], date(2099, 1, 1))


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


def test_adding_checks_the_start_day_is_in_range() -> None:
    with pytest.raises(OutOfRangeError):
        add_business_days(RELEASE, ["US-FEDWIRE"], date(2099, 1, 1), 0)


def test_adding_past_the_end_of_coverage_is_out_of_range() -> None:
    with pytest.raises(OutOfRangeError):
        add_business_days(RELEASE, ["US-FEDWIRE"], date(2027, 12, 30), 2)


def test_next_business_day_is_never_the_day_itself() -> None:
    shifted = next_business_day(RELEASE, ["US-FEDWIRE"], date(2026, 11, 25))

    assert (shifted.date, shifted.skipped) == (date(2026, 11, 27), (THANKSGIVING,))


def test_no_calendars_is_an_error() -> None:
    with pytest.raises(ValueError, match="At least one"):
        is_business_day(RELEASE, [], THANKSGIVING)


def test_unknown_calendar_is_an_error() -> None:
    with pytest.raises(UnknownCalendarError, match="no calendar") as raised:
        is_business_day(RELEASE, ["XX-NONE"], THANKSGIVING)

    assert raised.value.calendar_id == "XX-NONE"


def test_naive_as_of_is_rejected() -> None:
    with pytest.raises(ValueError, match="time zone"):
        is_business_day(RELEASE, ["US-FEDWIRE"], THANKSGIVING, as_of=datetime(2026, 1, 1))  # noqa: DTZ001


def test_entries_keep_their_evidence() -> None:
    (version,) = RELEASE.calendar("US-FEDWIRE").entries[THANKSGIVING]

    assert version.basis is Basis.PUBLISHED
    assert version.sources[0].url == "https://example.com/holidays"


def test_schema_version_1_reads_every_entry_as_current() -> None:
    release = parse_release(release_bytes(schema_version=1))

    assert not is_business_day(release, ["US-FEDWIRE"], date(2026, 1, 1))


def test_malformed_release_is_rejected() -> None:
    with pytest.raises(ReleaseError):
        parse_release(b'{"schema_version": 2}')


def test_unsupported_schema_version_is_rejected() -> None:
    with pytest.raises(ReleaseError, match="Unsupported"):
        parse_release(release_bytes(schema_version=3))


def test_naive_timestamps_are_rejected() -> None:
    content = release_bytes().replace(b"2026-10-28T00:00:00+00:00", b"2026-10-28T00:00:00")

    with pytest.raises(ReleaseError, match="time zone"):
        parse_release(content)


@given(st.dates(date(2026, 1, 1), date(2027, 12, 1)), st.integers(min_value=1, max_value=20))
def test_added_days_land_on_a_business_day_after_the_start(start: date, days: int) -> None:
    shifted = add_business_days(RELEASE, ["US-FEDWIRE"], start, days)

    assert shifted.date > start
    assert is_business_day(RELEASE, ["US-FEDWIRE"], shifted.date)
    assert all(not is_business_day(RELEASE, ["US-FEDWIRE"], day) for day in shifted.skipped)
    assert (shifted.date - start).days == days + len(shifted.skipped)


@given(st.dates(date(2026, 2, 1), date(2027, 12, 1)), st.integers(min_value=1, max_value=20))
def test_adding_then_subtracting_returns_on_or_before_the_start(start: date, days: int) -> None:
    there = add_business_days(RELEASE, ["US-FEDWIRE"], start, days).date
    back = add_business_days(RELEASE, ["US-FEDWIRE"], there, -days).date

    assert back <= start
    assert back > start - timedelta(days=days * 3)


@given(st.dates(date(2026, 1, 1), date(2027, 12, 1)))
def test_next_business_day_is_adding_one(start: date) -> None:
    assert next_business_day(RELEASE, ["US-FEDWIRE"], start) == add_business_days(
        RELEASE, ["US-FEDWIRE"], start, 1
    )


def a_case(expected: dict[str, object], **request: object) -> dict[str, object]:
    return {
        "id": "case",
        "dataset_release": "2026.10.28.1",
        "input": {"calendars": ["US-FEDWIRE"], **request},
        "expected": expected,
    }


def test_case_passes_when_the_answer_matches() -> None:
    case = a_case({"is_business_day": False}, operation="is_business_day", date="2026-11-26")

    assert run_case(RELEASE, case).passed


def test_a_wrong_case_fails_with_the_difference() -> None:
    case = a_case(
        {"date": "2026-11-26", "skipped": []},
        operation="add_business_days",
        date="2026-11-25",
        days=1,
    )

    result = run_case(RELEASE, case)

    assert result.outcome is Outcome.FAILED
    assert "2026-11-27" in result.detail


def test_an_expected_error_passes() -> None:
    case = a_case(
        {"error": "unknown_calendar"},
        operation="next_business_day",
        calendars=["XX-NONE"],
        date="2026-11-25",
    )

    assert run_case(RELEASE, case).passed


def test_out_of_range_is_not_applicable_to_a_real_release() -> None:
    case = a_case({"is_business_day": True}, operation="is_business_day", date="2099-01-02")

    assert run_case(RELEASE, case).outcome is Outcome.NOT_APPLICABLE
    assert run_case(RELEASE, case, golden=True).outcome is Outcome.FAILED


def test_cases_for_later_releases_do_not_apply() -> None:
    case = {"id": "later", "dataset_release": "2027.01.01.1", "input": {}, "expected": {}}

    assert run_cases(RELEASE, [case]) == []
    assert release_version("2026.10.28.10") > release_version("2026.10.28.9")


def test_as_of_cases_are_answered_from_the_history() -> None:
    case = a_case(
        {"is_business_day": True},
        operation="is_business_day",
        date="2026-11-26",
        as_of="2026-02-01T00:00:00Z",
    )

    assert run_case(RELEASE, case).passed
