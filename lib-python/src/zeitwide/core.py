"""``ZeitWide``: one API for business-day answers, offline or from the hosted API."""

from collections.abc import Sequence
from datetime import date, datetime
from types import TracebackType
from typing import Self

import httpx

from zeitwide import bundled
from zeitwide.answers import (
    ClosureList,
    DayAnswer,
    ShiftAnswer,
    answer_closures,
    answer_day,
    answer_shift,
)
from zeitwide.client import DEFAULT_BASE_URL, DEFAULT_TIMEOUT, ZeitwideClient
from zeitwide.engine import Combine
from zeitwide.release import Release

type Calendars = str | Sequence[str]


class ZeitWide:
    """Business-day answers with their evidence.

    ``ZeitWide()`` answers offline from the dataset release bundled with the library, with
    no network. ``ZeitWide(key="zw_live_…")`` asks the hosted API instead: the latest
    data, ``as_of`` back to the first recorded version, and a signed receipt with every
    answer. Both return the same types, shaped like the API's responses, and raise
    ``OutOfRangeError`` and ``UnknownCalendarError`` alike.

    Args:
        key: An API key. Without one, answers come from ``release`` offline.
        release: The release to answer from offline; the bundled latest one by default.
        base_url: The hosted API, when ``key`` is given.
        transport: For tests, an ``httpx.MockTransport``.
        timeout: Per request to the hosted API.
    """

    def __init__(
        self,
        key: str | None = None,
        *,
        release: Release | None = None,
        base_url: str = DEFAULT_BASE_URL,
        transport: httpx.BaseTransport | None = None,
        timeout: httpx.Timeout = DEFAULT_TIMEOUT,
    ) -> None:
        if key is not None and release is not None:
            raise ValueError("Pass a key to ask the API, or a release to answer offline, not both")
        self._client = (
            None
            if key is None
            else ZeitwideClient(base_url, key, transport=transport, timeout=timeout)
        )
        self._release = release

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
        if self._client is not None:
            self._client.close()

    @property
    def offline(self) -> bool:
        return self._client is None

    @property
    def release(self) -> Release:
        """The release offline answers come from. Loaded on first use, not at import."""
        if self._release is None:
            self._release = bundled.latest_release()
        return self._release

    def is_business_day(
        self,
        calendars: Calendars,
        day: date,
        combine: Combine | str = Combine.ALL_OPEN,
        *,
        as_of: datetime | None = None,
    ) -> DayAnswer:
        """Whether ``day`` is a business day on every calendar (``all_open``) or any one.

        Raises:
            UnknownCalendarError: A calendar isn't in the dataset.
            OutOfRangeError: ``day`` is outside a calendar's source coverage.
        """
        ids, rule = _ids(calendars), Combine(combine)
        if self._client is None:
            return answer_day(self.release, ids, day, rule, as_of)
        return DayAnswer.from_json(self._client.check(ids, day, rule.value, as_of=as_of))

    def next_business_day(
        self,
        calendars: Calendars,
        day: date,
        combine: Combine | str = Combine.ALL_OPEN,
        *,
        as_of: datetime | None = None,
    ) -> ShiftAnswer:
        """The first business day after ``day`` (never ``day`` itself), and the days skipped."""
        ids, rule = _ids(calendars), Combine(combine)
        if self._client is None:
            return answer_shift(self.release, ids, day, None, combine=rule, as_of=as_of)
        return ShiftAnswer.from_json(
            self._client.next_business_day(ids, day, rule.value, as_of=as_of)
        )

    def add_business_days(
        self,
        calendars: Calendars,
        day: date,
        days: int,
        combine: Combine | str = Combine.ALL_OPEN,
        *,
        as_of: datetime | None = None,
    ) -> ShiftAnswer:
        """Moves ``days`` business days from ``day``, backwards when negative (at most 1000).

        ``day`` itself doesn't count, and 0 returns ``day``.
        """
        ids, rule = _ids(calendars), Combine(combine)
        if self._client is None:
            return answer_shift(self.release, ids, day, days, combine=rule, as_of=as_of)
        return ShiftAnswer.from_json(
            self._client.add_business_days(ids, day, days, rule.value, as_of=as_of)
        )

    def list_closures(
        self, calendar_id: str, start: date, end: date, *, as_of: datetime | None = None
    ) -> ClosureList:
        """The closures on one calendar from ``start`` to ``end``, both inclusive (at most 731
        days). The whole range must be within coverage, so an empty list means no closures."""
        if self._client is None:
            return answer_closures(self.release, calendar_id, start, end, as_of)
        return ClosureList.from_json(self._client.closures(calendar_id, start, end, as_of=as_of))

    def explain_date(
        self, calendar_id: str, day: date, *, as_of: datetime | None = None
    ) -> DayAnswer:
        """One calendar on one date: the entry that decided it, with its sources, or the
        weekend rule."""
        if self._client is None:
            return answer_day(self.release, [calendar_id], day, Combine.ALL_OPEN, as_of)
        return DayAnswer.from_json(self._client.day(calendar_id, day, as_of=as_of))


def _ids(calendars: Calendars) -> list[str]:
    return [calendars] if isinstance(calendars, str) else list(calendars)
