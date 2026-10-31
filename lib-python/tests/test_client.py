"""The online client against a mocked transport, and the generated types against openapi.yaml."""

import json
import subprocess
import sys
from datetime import UTC, date, datetime
from pathlib import Path

import httpx
import pytest

from zeitwide.client import ApiError, ZeitwideClient

REPO_ROOT = Path(__file__).parents[2]
SPEC = REPO_ROOT / "api" / "openapi.yaml"
API_TYPES = REPO_ROOT / "lib-python" / "src" / "zeitwide" / "api_types.py"
# The same arguments as `make generate-clients`.
GENERATE = (
    "--input-file-type",
    "openapi",
    "--output-model-type",
    "typing.TypedDict",
    "--target-python-version",
    "3.12",
    "--disable-timestamp",
    "--use-standard-collections",
    "--use-union-operator",
    "--use-schema-description",
    "--use-field-description",
    "--no-use-closed-typed-dict",
    "--formatters",
    "builtin",
)
ANSWER = {"date": "2026-11-26", "is_business_day": False, "dataset_release": "2026.10.29.0"}


def client_for(status: int, body: object, seen: list[httpx.Request]) -> ZeitwideClient:
    def handle(request: httpx.Request) -> httpx.Response:
        seen.append(request)
        return httpx.Response(status, json=body)

    return ZeitwideClient(
        "https://api.test", api_key="zw_test_key", transport=httpx.MockTransport(handle)
    )


def test_day_uses_the_documented_path_and_key() -> None:
    seen: list[httpx.Request] = []
    with client_for(200, ANSWER, seen) as client:
        answer = client.day("US-FEDWIRE", date(2026, 11, 26))

    assert answer["is_business_day"] is False
    assert str(seen[0].url) == "https://api.test/v1/calendars/US-FEDWIRE/days/2026-11-26"
    assert seen[0].headers["Authorization"] == "Bearer zw_test_key"


def test_as_of_is_sent_with_its_time_zone() -> None:
    seen: list[httpx.Request] = []
    as_of = datetime(2022, 9, 9, 12, tzinfo=UTC)
    with client_for(200, ANSWER, seen) as client:
        client.day("GB-CHAPS", date(2022, 9, 19), as_of=as_of)
        client.calendars(as_of=as_of)
        client.closures("GB-CHAPS", date(2022, 1, 1), date(2022, 12, 31))

    assert seen[0].url.params["as_of"] == "2022-09-09T12:00:00+00:00"
    assert seen[1].url.path == "/v1/calendars"
    assert dict(seen[2].url.params) == {"from": "2022-01-01", "to": "2022-12-31"}


def test_posts_carry_the_documented_bodies() -> None:
    seen: list[httpx.Request] = []
    as_of = datetime(2026, 10, 1, tzinfo=UTC)
    with client_for(200, ANSWER, seen) as client:
        client.check(["US-FEDWIRE", "EU-T2"], date(2026, 4, 3), "any_open", as_of=as_of)
        client.add_business_days(["US-FEDWIRE"], date(2026, 11, 24), 2, as_of=as_of)
        client.next_business_day(["US-FEDWIRE"], date(2026, 11, 25), as_of=as_of)

    bodies = [(request.url.path, json.loads(request.content)) for request in seen]
    assert bodies == [
        (
            "/v1/business-days/check",
            {
                "calendars": ["US-FEDWIRE", "EU-T2"],
                "date": "2026-04-03",
                "combine": "any_open",
                "as_of": "2026-10-01T00:00:00+00:00",
            },
        ),
        (
            "/v1/business-days/add",
            {
                "calendars": ["US-FEDWIRE"],
                "date": "2026-11-24",
                "days": 2,
                "combine": "all_open",
                "as_of": "2026-10-01T00:00:00+00:00",
            },
        ),
        (
            "/v1/business-days/next",
            {
                "calendars": ["US-FEDWIRE"],
                "date": "2026-11-25",
                "combine": "all_open",
                "as_of": "2026-10-01T00:00:00+00:00",
            },
        ),
    ]


def test_problem_details_become_an_api_error() -> None:
    problem = {"title": "Out of range", "status": 422, "code": "out_of_range", "detail": "2099"}
    with client_for(422, problem, []) as client, pytest.raises(ApiError, match="out_of_range"):
        client.check(["US-FEDWIRE"], date(2099, 1, 2))

    limited = {**problem, "status": 429, "code": "rate_limited"}
    with client_for(429, limited, []) as client, pytest.raises(ApiError) as raised:
        client.calendars()
    assert (raised.value.status, raised.value.code) == (429, "rate_limited")


def test_naive_as_of_is_rejected() -> None:
    with client_for(200, ANSWER, []) as client, pytest.raises(ValueError, match="time zone"):
        client.day("US-FEDWIRE", date(2026, 11, 26), as_of=datetime(2026, 1, 1))  # noqa: DTZ001


def test_generated_types_match_openapi(tmp_path: Path) -> None:
    output = tmp_path / "api_types.py"
    command = [sys.executable, "-m", "datamodel_code_generator", "--input", str(SPEC)]
    subprocess.run(  # noqa: S603 - fixed arguments, no shell
        [*command, *GENERATE, "--output", str(output)], check=True, capture_output=True
    )

    expected = output.read_text(encoding="utf-8")
    assert API_TYPES.read_text(encoding="utf-8") == expected, "run make generate-clients"
