"""ZeitWide() offline: the bundled release, the answers' shape, and the conformance suite."""

import json
from datetime import UTC, date, datetime
from pathlib import Path

import jsonschema
import pytest
from hypothesis import given
from hypothesis import strategies as st

from zeitwide import (
    Basis,
    ClosureList,
    DayAnswer,
    DecidedBy,
    OutOfRangeError,
    ShiftAnswer,
    UnknownCalendarError,
    ZeitWide,
    load_release,
)
from zeitwide.bundled import latest_release, latest_release_file
from zeitwide.conformance import load_cases
from zeitwide.release import release_version

REPO_ROOT = Path(__file__).parents[2]
CASES = REPO_ROOT / "conformance" / "cases"
GOLDEN = REPO_ROOT / "conformance" / "dataset.json"
RELEASES = REPO_ROOT / "dataset" / "releases"
SPEC = REPO_ROOT / "api" / "openapi.yaml"
THANKSGIVING = date(2026, 11, 26)


@pytest.fixture(scope="module")
def zw() -> ZeitWide:
    return ZeitWide()


def test_bundled_release_is_the_latest_committed_release() -> None:
    newest = max(RELEASES.glob("*.json"), key=lambda path: release_version(path.stem))

    assert latest_release_file().name == newest.name
    assert latest_release_file().read_bytes() == newest.read_bytes()
    assert latest_release().id == newest.stem


def test_offline_answers_need_no_key_and_name_the_release(zw: ZeitWide) -> None:
    answer = zw.is_business_day("US-FEDWIRE", THANKSGIVING)

    assert zw.offline
    assert answer.is_business_day is False
    assert answer.dataset_release == latest_release().id
    assert answer.as_of == latest_release().as_of
    assert answer.receipt is None
    assert answer.calendar_revisions == {"US-FEDWIRE": 1}


def test_explain_date_gives_the_deciding_entry_and_its_sources(zw: ZeitWide) -> None:
    (day,) = zw.explain_date("US-FEDWIRE", THANKSGIVING).calendars

    assert day.decided_by is DecidedBy.ENTRY
    assert day.is_open is False
    assert day.reason == "Thanksgiving Day"
    assert day.basis is Basis.PUBLISHED
    assert day.sources
    assert all(len(source.snapshot_sha256) == 64 for source in day.sources)


def test_explain_date_on_a_weekend_names_the_weekend_rule(zw: ZeitWide) -> None:
    (day,) = zw.explain_date("EU-T2", date(2026, 11, 28)).calendars

    assert day.decided_by is DecidedBy.WEEKEND_RULE
    assert (day.is_open, day.reason, day.sources) == (False, None, ())


def test_next_business_day_explains_each_skipped_day(zw: ZeitWide) -> None:
    answer = zw.next_business_day(["US-FEDWIRE", "EU-T2"], date(2026, 11, 25))

    assert (answer.start, answer.days, answer.date) == (date(2026, 11, 25), 1, date(2026, 11, 27))
    (skipped,) = answer.skipped
    assert skipped.date == THANKSGIVING
    assert [day.is_open for day in skipped.calendars] == [False, True]


def test_add_business_days_any_open(zw: ZeitWide) -> None:
    answer = zw.add_business_days(["US-FEDWIRE", "EU-T2"], date(2026, 11, 25), 1, "any_open")

    assert answer.date == THANKSGIVING
    assert answer.skipped == ()


def test_list_closures_returns_only_closures_in_order(zw: ZeitWide) -> None:
    closures = zw.list_closures("US-FEDWIRE", date(2026, 11, 1), date(2026, 12, 31))

    assert [closure.date for closure in closures.closures] == [
        date(2026, 11, 11),
        THANKSGIVING,
        date(2026, 12, 25),
    ]
    assert closures.calendar_revision == 1


@pytest.mark.parametrize(
    ("start", "end"),
    [(date(2026, 2, 1), date(2026, 1, 1)), (date(2026, 1, 1), date(2028, 1, 2))],
)
def test_list_closures_refuses_a_bad_or_long_range(zw: ZeitWide, start: date, end: date) -> None:
    with pytest.raises(ValueError, match="731"):
        zw.list_closures("US-FEDWIRE", start, end)


def test_dates_past_coverage_are_out_of_range_never_guessed(zw: ZeitWide) -> None:
    with pytest.raises(OutOfRangeError):
        zw.is_business_day("US-FEDWIRE", date(2030, 1, 2))
    with pytest.raises(OutOfRangeError):
        zw.list_closures("US-FEDWIRE", date(2027, 6, 1), date(2028, 1, 31))


def test_unknown_calendars_and_limits(zw: ZeitWide) -> None:
    with pytest.raises(UnknownCalendarError):
        zw.is_business_day("XX-NOWHERE", THANKSGIVING)
    with pytest.raises(ValueError, match="1 to 10"):
        zw.is_business_day([], THANKSGIVING)
    with pytest.raises(ValueError, match="1000"):
        zw.add_business_days("US-FEDNOW", THANKSGIVING, 1001)
    with pytest.raises(ValueError, match="time zone"):
        zw.is_business_day("US-FEDWIRE", THANKSGIVING, as_of=datetime(2026, 11, 1))  # noqa: DTZ001


def test_as_of_later_than_the_release_is_answered_as_of_the_release(zw: ZeitWide) -> None:
    later = datetime(2030, 1, 1, tzinfo=UTC)

    assert zw.is_business_day("US-FEDWIRE", THANKSGIVING, as_of=later).as_of == zw.release.as_of


def test_as_of_before_anything_was_recorded_is_out_of_range(zw: ZeitWide) -> None:
    with pytest.raises(OutOfRangeError):
        zw.is_business_day("US-FEDWIRE", THANKSGIVING, as_of=datetime(2026, 1, 1, tzinfo=UTC))


def test_a_key_and_a_release_together_are_refused() -> None:
    with pytest.raises(ValueError, match="not both"):
        ZeitWide("zw_live_x", release=latest_release())


def test_answers_round_trip_through_the_api_json(zw: ZeitWide) -> None:
    day = zw.is_business_day(["US-FEDWIRE", "GB-CHAPS"], THANKSGIVING)
    shift = zw.add_business_days(["US-FEDWIRE"], date(2026, 12, 23), 3)
    closures = zw.list_closures("GB-CHAPS", date(2026, 1, 1), date(2026, 12, 31))

    assert DayAnswer.from_json(day.to_json()) == day
    assert ShiftAnswer.from_json(shift.to_json()) == shift
    assert ClosureList.from_json(closures.to_json()) == closures


def test_answer_json_matches_the_openapi_schemas(zw: ZeitWide) -> None:
    yaml = pytest.importorskip("yaml")
    spec = yaml.safe_load(SPEC.read_text(encoding="utf-8"))
    schemas = spec["components"]["schemas"]
    # Offline answers carry no receipt; everything else must match the API's contract.
    for schema in schemas.values():
        if "receipt" in schema.get("required", []):
            schema["required"] = [field for field in schema["required"] if field != "receipt"]
    answers = {
        "DayAnswer": zw.is_business_day(["US-FEDWIRE", "EU-T2"], THANKSGIVING).to_json(),
        "Shifted": zw.next_business_day(["US-FEDWIRE"], date(2026, 12, 24)).to_json(),
        "ClosureList": zw.list_closures("EU-T2", date(2026, 1, 1), date(2026, 12, 31)).to_json(),
    }
    for name, answer in answers.items():
        schema = {**schemas[name], "components": {"schemas": schemas}}
        # Embedding the components lets the spec's "#/components/..." refs resolve.
        jsonschema.validate(json.loads(json.dumps(answer)), schema)


@pytest.mark.parametrize("case", load_cases(CASES), ids=lambda case: str(case["id"]))
def test_the_public_api_passes_every_conformance_case(case: dict[str, object]) -> None:
    zw = ZeitWide(release=load_release(GOLDEN))
    request, expected = case["input"], case["expected"]
    assert isinstance(request, dict)
    assert isinstance(expected, dict)

    assert _ask(zw, request) == expected


def _ask(zw: ZeitWide, request: dict[str, object]) -> dict[str, object]:
    calendars = [str(calendar) for calendar in request["calendars"]]  # type: ignore[attr-defined]
    day = date.fromisoformat(str(request["date"]))
    combine = str(request.get("combine", "all_open"))
    as_of = datetime.fromisoformat(str(request["as_of"])) if "as_of" in request else None
    try:
        if request["operation"] == "is_business_day":
            return {
                "is_business_day": zw.is_business_day(
                    calendars, day, combine, as_of=as_of
                ).is_business_day
            }
        if request["operation"] == "next_business_day":
            shifted = zw.next_business_day(calendars, day, combine, as_of=as_of)
        else:
            days = int(str(request["days"]))
            shifted = zw.add_business_days(calendars, day, days, combine, as_of=as_of)
    except OutOfRangeError:
        return {"error": "out_of_range"}
    except UnknownCalendarError:
        return {"error": "unknown_calendar"}
    return {
        "date": shifted.date.isoformat(),
        "skipped": [skipped.date.isoformat() for skipped in shifted.skipped],
    }


@given(
    start=st.dates(date(2026, 1, 5), date(2027, 12, 1)),
    days=st.integers(min_value=-20, max_value=20),
)
def test_adding_never_lands_on_a_closed_day(start: date, days: int) -> None:
    zw = ZeitWide()
    try:
        answer = zw.add_business_days("GB-CHAPS", start, days)
    except OutOfRangeError:
        return
    if days:
        assert zw.is_business_day("GB-CHAPS", answer.date).is_business_day
        assert all(not day.calendars[0].is_open for day in answer.skipped)
