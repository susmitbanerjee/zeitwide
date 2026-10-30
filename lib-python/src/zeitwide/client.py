"""Online client for the hosted Zeitwide API: ``pip install "zeitwide[online]"``.

The request and response types are generated from ``api/openapi.yaml`` into
``zeitwide.api_types``. The API answers with the same engine and conformance suite as this
library, plus history after the bundled release and evidence for every answer. Offline answers
need none of this: see ``zeitwide.engine``.
"""

from collections.abc import Mapping, Sequence
from datetime import date, datetime
from types import TracebackType
from typing import Self, cast

import httpx

from zeitwide.api_types import (
    AddRequest,
    CalendarList,
    ClosureList,
    Combine,
    DayAnswer,
    DayRequest,
    Problem,
    Shifted,
)

DEFAULT_BASE_URL = "https://api.zeitwide.com"
DEFAULT_TIMEOUT = httpx.Timeout(10.0, connect=5.0)


class ApiError(Exception):
    """The API answered with problem details, such as ``out_of_range`` or ``rate_limited``."""

    def __init__(self, problem: Problem) -> None:
        super().__init__(
            f"{problem['status']} {problem['code']}: {problem.get('detail', problem['title'])}"
        )
        self.problem = problem

    @property
    def code(self) -> str:
        return self.problem["code"]

    @property
    def status(self) -> int:
        return self.problem["status"]


class ZeitwideClient:
    """A small synchronous client. Use it as a context manager, or call ``close()``.

    Args:
        base_url: The API's address.
        api_key: Optional; without one, requests share a low anonymous limit.
        transport: For tests, an ``httpx.MockTransport``.
        timeout: Per request; connect within 5 s and finish within 10 s by default.
    """

    def __init__(
        self,
        base_url: str = DEFAULT_BASE_URL,
        api_key: str | None = None,
        *,
        transport: httpx.BaseTransport | None = None,
        timeout: httpx.Timeout = DEFAULT_TIMEOUT,
    ) -> None:
        headers = {} if api_key is None else {"Authorization": f"Bearer {api_key}"}
        self._http = httpx.Client(
            base_url=base_url, headers=headers, timeout=timeout, transport=transport
        )

    def __enter__(self) -> Self:
        return self

    def __exit__(
        self,
        exc_type: type[BaseException] | None,
        exc: BaseException | None,
        traceback: TracebackType | None,
    ) -> None:
        self.close()

    def close(self) -> None:
        self._http.close()

    def calendars(self, *, as_of: datetime | None = None) -> CalendarList:
        return cast(CalendarList, self._get("/v1/calendars", _as_of(as_of)))

    def day(self, calendar_id: str, day: date, *, as_of: datetime | None = None) -> DayAnswer:
        path = f"/v1/calendars/{calendar_id}/days/{day.isoformat()}"
        return cast(DayAnswer, self._get(path, _as_of(as_of)))

    def closures(
        self, calendar_id: str, start: date, end: date, *, as_of: datetime | None = None
    ) -> ClosureList:
        params = {"from": start.isoformat(), "to": end.isoformat(), **_as_of(as_of)}
        return cast(ClosureList, self._get(f"/v1/calendars/{calendar_id}/closures", params))

    def check(
        self,
        calendar_ids: Sequence[str],
        day: date,
        combine: Combine = "all_open",
        *,
        as_of: datetime | None = None,
    ) -> DayAnswer:
        body: DayRequest = {"calendars": list(calendar_ids), "date": day.isoformat()}
        body["combine"] = combine
        if as_of is not None:
            body["as_of"] = _instant(as_of)
        return cast(DayAnswer, self._post("/v1/business-days/check", body))

    def add_business_days(
        self,
        calendar_ids: Sequence[str],
        day: date,
        days: int,
        combine: Combine = "all_open",
        *,
        as_of: datetime | None = None,
    ) -> Shifted:
        body: AddRequest = {"calendars": list(calendar_ids), "date": day.isoformat(), "days": days}
        body["combine"] = combine
        if as_of is not None:
            body["as_of"] = _instant(as_of)
        return cast(Shifted, self._post("/v1/business-days/add", body))

    def next_business_day(
        self,
        calendar_ids: Sequence[str],
        day: date,
        combine: Combine = "all_open",
        *,
        as_of: datetime | None = None,
    ) -> Shifted:
        body: DayRequest = {"calendars": list(calendar_ids), "date": day.isoformat()}
        body["combine"] = combine
        if as_of is not None:
            body["as_of"] = _instant(as_of)
        return cast(Shifted, self._post("/v1/business-days/next", body))

    def _get(self, path: str, params: Mapping[str, str]) -> object:
        return _json(self._http.get(path, params=params))

    def _post(self, path: str, body: Mapping[str, object]) -> object:
        return _json(self._http.post(path, json=body))


def _json(response: httpx.Response) -> object:
    payload: object = response.json()
    if response.is_success:
        return payload
    raise ApiError(cast(Problem, payload))


def _as_of(as_of: datetime | None) -> dict[str, str]:
    return {} if as_of is None else {"as_of": _instant(as_of)}


def _instant(moment: datetime) -> str:
    if moment.tzinfo is None:
        raise ValueError("as_of needs a time zone")
    return moment.isoformat()
