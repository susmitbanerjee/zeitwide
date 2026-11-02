"""ZeitWide(key=...) against a mocked hosted API: same answers, receipts and errors as offline."""

import json
from datetime import UTC, date, datetime

import httpx
import pytest

from zeitwide import ApiError, OutOfRangeError, UnknownCalendarError, ZeitWide

THANKSGIVING = date(2026, 11, 26)
STAMP = {
    "as_of": "2026-10-29T03:15:37.300151Z",
    "dataset_release": "2026.10.29.1",
    "calendar_revisions": {"US-FEDWIRE": 1},
    "receipt": {
        "id": "rcpt_" + "a" * 32,
        "payload": {"request": {}, "answer": {}, "issued_at": "2026-11-02T10:00:00Z"},
        "payload_hash": "a" * 64,
        "key_id": "zw-2026-10",
        "signature": "A" * 86,
    },
}
CLOSED = {
    "calendar_id": "US-FEDWIRE",
    "is_open": False,
    "decided_by": "entry",
    "reason": "Thanksgiving Day",
    "basis": "published",
    "revision": 1,
    "recorded_from": "2026-10-29T02:03:13.894546Z",
    "sources": [
        {
            "publisher": "federalreserve-k8",
            "url": "https://www.federalreserve.gov/aboutthefed/k8.htm",
            "retrieved_at": "2026-10-29T02:03:00.007366Z",
            "snapshot_sha256": "4" * 64,
        }
    ],
}
OPEN = {"calendar_id": "US-FEDWIRE", "is_open": True, "decided_by": "weekend_rule", "sources": []}
DAY = {
    "date": "2026-11-26",
    "is_business_day": False,
    "combine": "all_open",
    "calendars": [CLOSED],
    **STAMP,
}
SHIFTED = {
    "start": "2026-11-25",
    "days": 1,
    "date": "2026-11-27",
    "combine": "all_open",
    "calendars": [OPEN],
    "skipped": [{"date": "2026-11-26", "calendars": [CLOSED]}],
    **STAMP,
}
CLOSURES = {
    "calendar_id": "US-FEDWIRE",
    "from": "2026-11-01",
    "to": "2026-11-30",
    "closures": [
        {key: CLOSED[key] for key in ("reason", "basis", "revision", "recorded_from", "sources")}
        | {"date": "2026-11-26"}
    ],
    "calendar_revision": 1,
    **STAMP,
}


def live(status: int, body: object, seen: list[httpx.Request]) -> ZeitWide:
    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(status, json=body)

    return ZeitWide(
        "zw_live_test", base_url="https://api.test", transport=httpx.MockTransport(handle)
    )


def test_is_business_day_posts_the_question_with_the_key() -> None:
    seen: list[httpx.Request] = []
    as_of = datetime(2026, 11, 1, tzinfo=UTC)

    with live(200, DAY, seen) as zw:
        answer = zw.is_business_day("US-FEDWIRE", THANKSGIVING, as_of=as_of)

    assert not zw.offline
    assert answer.is_business_day is False
    assert answer.calendars[0].reason == "Thanksgiving Day"
    assert answer.receipt is not None
    assert answer.receipt.key_id == "zw-2026-10"
    assert answer.to_json() == DAY
    (request,) = seen
    assert request.url.path == "/v1/business-days/check"
    assert request.headers["Authorization"] == "Bearer zw_live_test"
    assert json.loads(request.content) == {
        "calendars": ["US-FEDWIRE"],
        "date": "2026-11-26",
        "combine": "all_open",
        "as_of": "2026-11-01T00:00:00+00:00",
    }


def test_next_and_add_business_days() -> None:
    seen: list[httpx.Request] = []
    zw = live(200, SHIFTED, seen)

    assert zw.next_business_day(["US-FEDWIRE"], date(2026, 11, 25)).to_json() == SHIFTED
    assert zw.add_business_days("US-FEDWIRE", date(2026, 11, 25), 1, "any_open").date == date(
        2026, 11, 27
    )
    assert [request.url.path for request in seen] == [
        "/v1/business-days/next",
        "/v1/business-days/add",
    ]
    assert json.loads(seen[1].content)["combine"] == "any_open"


def test_explain_date_and_list_closures_use_the_calendar_routes() -> None:
    seen: list[httpx.Request] = []

    assert live(200, DAY, seen).explain_date("US-FEDWIRE", THANKSGIVING).to_json() == DAY
    closures = live(200, CLOSURES, seen).list_closures(
        "US-FEDWIRE", date(2026, 11, 1), date(2026, 11, 30)
    )

    assert closures.to_json() == CLOSURES
    assert seen[0].url.path == "/v1/calendars/US-FEDWIRE/days/2026-11-26"
    assert seen[1].url.path == "/v1/calendars/US-FEDWIRE/closures"
    assert seen[1].url.params["from"] == "2026-11-01"


@pytest.mark.parametrize(
    ("code", "error"),
    [("out_of_range", OutOfRangeError), ("unknown_calendar", UnknownCalendarError)],
)
def test_api_errors_are_the_offline_errors_too(code: str, error: type[Exception]) -> None:
    problem = {"title": "No", "status": 422, "code": code, "detail": "outside coverage"}

    with pytest.raises(error) as raised:
        live(422, problem, []).is_business_day("US-FEDWIRE", date(2030, 1, 2))

    assert isinstance(raised.value, ApiError)
    assert raised.value.code == code
    assert getattr(raised.value, "calendar_id", "unset") is None


def test_other_api_errors_stay_api_errors() -> None:
    problem = {"title": "Slow down", "status": 429, "code": "rate_limited"}

    with pytest.raises(ApiError, match="429 rate_limited") as raised:
        live(429, problem, []).is_business_day("US-FEDWIRE", THANKSGIVING)

    assert not isinstance(raised.value, OutOfRangeError)


def test_a_non_json_error_page_still_raises_an_api_error() -> None:
    zw = ZeitWide(
        "zw_live_test",
        transport=httpx.MockTransport(lambda _: httpx.Response(502, text="<html>Bad gateway")),
    )

    with pytest.raises(ApiError) as raised:
        zw.is_business_day("US-FEDWIRE", THANKSGIVING)

    assert raised.value.status == 502
