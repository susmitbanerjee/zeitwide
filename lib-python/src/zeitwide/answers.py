"""Answers with their evidence, in the shape of the REST API's responses.

``ZeitWide`` returns these offline and online alike. Offline they are built here from a
release by ``zeitwide.engine``; online they are read from the API's JSON. ``to_json()``
gives back that JSON, which is what the MCP tools return. Pure: no I/O, no clock.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime, timedelta
from enum import StrEnum
from typing import Any, cast

from zeitwide.engine import (
    Combine,
    add_business_days,
    check_coverage,
    is_business_day,
    is_open,
    next_business_day,
)
from zeitwide.release import Basis, Entry, Release

# The API's limits, so that offline and online refuse the same questions.
MAX_CALENDARS = 10
MAX_DAYS = 1000
MAX_CLOSURE_RANGE_DAYS = 731

type Day = date
type Json = dict[str, Any]


class DecidedBy(StrEnum):
    ENTRY = "entry"
    WEEKEND_RULE = "weekend_rule"


@dataclass(frozen=True, slots=True)
class Source:
    """A snapshot of an official source that backs an entry."""

    publisher: str
    url: str
    retrieved_at: datetime
    snapshot_sha256: str

    def to_json(self) -> Json:
        return {
            "publisher": self.publisher,
            "url": self.url,
            "retrieved_at": _instant(self.retrieved_at),
            "snapshot_sha256": self.snapshot_sha256,
        }

    @classmethod
    def from_json(cls, raw: Mapping[str, Any]) -> "Source":
        return cls(
            publisher=str(raw["publisher"]),
            url=str(raw["url"]),
            retrieved_at=_parse_instant(raw["retrieved_at"]),
            snapshot_sha256=str(raw["snapshot_sha256"]),
        )


@dataclass(frozen=True, slots=True)
class Receipt:
    """A signed record of one answer. Check it with ``zeitwide.receipts.verify_receipt``.

    The payload is kept exactly as the API sent it: the signature covers its canonical JSON.
    """

    id: str
    payload: Mapping[str, Any]
    payload_hash: str
    key_id: str
    signature: str

    def to_json(self) -> Json:
        return {
            "id": self.id,
            "payload": dict(self.payload),
            "payload_hash": self.payload_hash,
            "key_id": self.key_id,
            "signature": self.signature,
        }

    @classmethod
    def from_json(cls, raw: Mapping[str, Any]) -> "Receipt":
        payload = raw["payload"]
        if not isinstance(payload, Mapping):
            raise TypeError("A receipt's payload must be an object")
        return cls(
            id=str(raw["id"]),
            payload=payload,
            payload_hash=str(raw["payload_hash"]),
            key_id=str(raw["key_id"]),
            signature=str(raw["signature"]),
        )


@dataclass(frozen=True, slots=True)
class CalendarDay:
    """One calendar's answer for one date, and what decided it."""

    calendar_id: str
    is_open: bool
    decided_by: DecidedBy
    # These four are set only when an entry decided.
    reason: str | None
    basis: Basis | None
    revision: int | None
    recorded_from: datetime | None
    sources: tuple[Source, ...]

    def to_json(self) -> Json:
        json: Json = {
            "calendar_id": self.calendar_id,
            "is_open": self.is_open,
            "decided_by": self.decided_by.value,
        }
        if self.reason is not None:
            json["reason"] = self.reason
        if self.basis is not None:
            json["basis"] = self.basis.value
        if self.revision is not None:
            json["revision"] = self.revision
        if self.recorded_from is not None:
            json["recorded_from"] = _instant(self.recorded_from)
        json["sources"] = [source.to_json() for source in self.sources]
        return json

    @classmethod
    def from_json(cls, raw: Mapping[str, Any]) -> "CalendarDay":
        basis, recorded_from = raw.get("basis"), raw.get("recorded_from")
        return cls(
            calendar_id=str(raw["calendar_id"]),
            is_open=bool(raw["is_open"]),
            decided_by=DecidedBy(raw["decided_by"]),
            reason=None if raw.get("reason") is None else str(raw["reason"]),
            basis=None if basis is None else Basis(basis),
            revision=None if raw.get("revision") is None else int(raw["revision"]),
            recorded_from=None if recorded_from is None else _parse_instant(recorded_from),
            sources=tuple(Source.from_json(source) for source in raw["sources"]),
        )


@dataclass(frozen=True, slots=True)
class DayAnswer:
    """Whether ``date`` is a business day across ``calendars``, with each calendar's reason."""

    date: Day
    is_business_day: bool
    combine: Combine
    calendars: tuple[CalendarDay, ...]
    # The instant answered as of: the one asked for, or the release's if that is earlier.
    as_of: datetime
    dataset_release: str
    calendar_revisions: Mapping[str, int]
    # None offline: only the hosted API signs answers.
    receipt: Receipt | None

    def to_json(self) -> Json:
        return {
            "date": self.date.isoformat(),
            "is_business_day": self.is_business_day,
            "combine": self.combine.value,
            "calendars": [calendar.to_json() for calendar in self.calendars],
            **_stamp_json(self.as_of, self.dataset_release, self.calendar_revisions, self.receipt),
        }

    @classmethod
    def from_json(cls, raw: Mapping[str, Any]) -> "DayAnswer":
        return cls(
            date=date.fromisoformat(raw["date"]),
            is_business_day=bool(raw["is_business_day"]),
            combine=Combine(raw["combine"]),
            calendars=_calendar_days(raw["calendars"]),
            **_stamp(raw),
        )


@dataclass(frozen=True, slots=True)
class SkippedDay:
    date: Day
    calendars: tuple[CalendarDay, ...]

    def to_json(self) -> Json:
        return {
            "date": self.date.isoformat(),
            "calendars": [calendar.to_json() for calendar in self.calendars],
        }


@dataclass(frozen=True, slots=True)
class ShiftAnswer:
    """Where moving ``days`` business days from ``start`` lands, and the days passed over."""

    start: Day
    days: int
    date: Day
    combine: Combine
    # Each calendar's answer for ``date``.
    calendars: tuple[CalendarDay, ...]
    # Days passed over because they weren't business days, in the order met.
    skipped: tuple[SkippedDay, ...]
    as_of: datetime
    dataset_release: str
    calendar_revisions: Mapping[str, int]
    receipt: Receipt | None

    def to_json(self) -> Json:
        return {
            "start": self.start.isoformat(),
            "days": self.days,
            "date": self.date.isoformat(),
            "combine": self.combine.value,
            "calendars": [calendar.to_json() for calendar in self.calendars],
            "skipped": [day.to_json() for day in self.skipped],
            **_stamp_json(self.as_of, self.dataset_release, self.calendar_revisions, self.receipt),
        }

    @classmethod
    def from_json(cls, raw: Mapping[str, Any]) -> "ShiftAnswer":
        return cls(
            start=date.fromisoformat(raw["start"]),
            days=int(raw["days"]),
            date=date.fromisoformat(raw["date"]),
            combine=Combine(raw["combine"]),
            calendars=_calendar_days(raw["calendars"]),
            skipped=tuple(
                SkippedDay(date.fromisoformat(day["date"]), _calendar_days(day["calendars"]))
                for day in raw["skipped"]
            ),
            **_stamp(raw),
        )


@dataclass(frozen=True, slots=True)
class Closure:
    date: Day
    reason: str
    basis: Basis
    revision: int
    recorded_from: datetime
    sources: tuple[Source, ...]

    def to_json(self) -> Json:
        return {
            "date": self.date.isoformat(),
            "reason": self.reason,
            "basis": self.basis.value,
            "revision": self.revision,
            "recorded_from": _instant(self.recorded_from),
            "sources": [source.to_json() for source in self.sources],
        }

    @classmethod
    def from_json(cls, raw: Mapping[str, Any]) -> "Closure":
        return cls(
            date=date.fromisoformat(raw["date"]),
            reason=str(raw["reason"]),
            basis=Basis(raw["basis"]),
            revision=int(raw["revision"]),
            recorded_from=_parse_instant(raw["recorded_from"]),
            sources=tuple(Source.from_json(source) for source in raw["sources"]),
        )


@dataclass(frozen=True, slots=True)
class ClosureList:
    """The closures on one calendar from ``start`` to ``end``, both inclusive."""

    calendar_id: str
    start: Day
    end: Day
    closures: tuple[Closure, ...]
    as_of: datetime
    dataset_release: str
    calendar_revision: int
    calendar_revisions: Mapping[str, int]
    receipt: Receipt | None

    def to_json(self) -> Json:
        return {
            "calendar_id": self.calendar_id,
            "from": self.start.isoformat(),
            "to": self.end.isoformat(),
            "closures": [closure.to_json() for closure in self.closures],
            "calendar_revision": self.calendar_revision,
            **_stamp_json(self.as_of, self.dataset_release, self.calendar_revisions, self.receipt),
        }

    @classmethod
    def from_json(cls, raw: Mapping[str, Any]) -> "ClosureList":
        return cls(
            calendar_id=str(raw["calendar_id"]),
            start=date.fromisoformat(raw["from"]),
            end=date.fromisoformat(raw["to"]),
            closures=tuple(Closure.from_json(closure) for closure in raw["closures"]),
            calendar_revision=int(raw["calendar_revision"]),
            **_stamp(raw),
        )


# Offline answers, built from a release.


def answer_day(
    release: Release,
    calendar_ids: Sequence[str],
    day: date,
    combine: Combine,
    as_of: datetime | None,
) -> DayAnswer:
    moment = effective_as_of(release, as_of)
    ids = _checked_ids(calendar_ids)
    answer = is_business_day(release, ids, day, combine, as_of=moment)
    return DayAnswer(
        date=day,
        is_business_day=answer,
        combine=combine,
        calendars=_explain(release, ids, day, moment),
        as_of=moment,
        dataset_release=release.id,
        calendar_revisions=_revisions(release, ids, moment),
        receipt=None,
    )


def answer_shift(  # noqa: PLR0913 - the question has this many parts
    release: Release,
    calendar_ids: Sequence[str],
    day: date,
    days: int | None,
    *,
    combine: Combine,
    as_of: datetime | None,
) -> ShiftAnswer:
    """``add_business_days``, or ``next_business_day`` when ``days`` is None."""
    moment = effective_as_of(release, as_of)
    ids = _checked_ids(calendar_ids)
    if days is None:
        shifted = next_business_day(release, ids, day, combine, as_of=moment)
    else:
        if abs(days) > MAX_DAYS:
            raise ValueError(f"days must be between -{MAX_DAYS} and {MAX_DAYS}")
        shifted = add_business_days(release, ids, day, days, combine, as_of=moment)
    return ShiftAnswer(
        start=day,
        days=1 if days is None else days,
        date=shifted.date,
        combine=combine,
        calendars=_explain(release, ids, shifted.date, moment),
        skipped=tuple(
            SkippedDay(skipped, _explain(release, ids, skipped, moment))
            for skipped in shifted.skipped
        ),
        as_of=moment,
        dataset_release=release.id,
        calendar_revisions=_revisions(release, ids, moment),
        receipt=None,
    )


def answer_closures(
    release: Release, calendar_id: str, start: date, end: date, as_of: datetime | None
) -> ClosureList:
    moment = effective_as_of(release, as_of)
    if end < start or end - start >= timedelta(days=MAX_CLOSURE_RANGE_DAYS):
        raise ValueError(
            f"The range must end on or after its start and cover at most "
            f"{MAX_CLOSURE_RANGE_DAYS} days"
        )
    calendar = release.calendar(calendar_id)
    # The whole range must be covered, so an empty list means no closures, never no data.
    for edge in (start, end):
        check_coverage(release, calendar_id, edge, moment)
    entries = [
        entry
        for day in sorted(calendar.entries)
        if start <= day <= end
        and (entry := calendar.entry_at(day, moment)) is not None
        and not entry.is_open
    ]
    return ClosureList(
        calendar_id=calendar_id,
        start=start,
        end=end,
        closures=tuple(
            Closure(
                date=entry.day,
                reason=entry.reason,
                basis=entry.basis,
                revision=entry.revision,
                recorded_from=entry.recorded_from,
                sources=_sources(entry),
            )
            for entry in entries
        ),
        as_of=moment,
        dataset_release=release.id,
        calendar_revision=calendar.revision_at(moment),
        calendar_revisions=_revisions(release, [calendar_id], moment),
        receipt=None,
    )


def effective_as_of(release: Release, as_of: datetime | None) -> datetime:
    """A release holds what was recorded up to its ``as_of``; a later instant would claim more."""
    if as_of is None:
        return release.as_of
    if as_of.tzinfo is None:
        raise ValueError("as_of needs a time zone")
    return min(as_of, release.as_of)


def _checked_ids(calendar_ids: Sequence[str]) -> list[str]:
    ids = list(calendar_ids)
    if not 1 <= len(ids) <= MAX_CALENDARS:
        raise ValueError(f"Ask about 1 to {MAX_CALENDARS} calendars")
    return ids


def _explain(
    release: Release, calendar_ids: Sequence[str], day: date, moment: datetime
) -> tuple[CalendarDay, ...]:
    return tuple(_calendar_day(release, calendar_id, day, moment) for calendar_id in calendar_ids)


def _calendar_day(release: Release, calendar_id: str, day: date, moment: datetime) -> CalendarDay:
    calendar = release.calendar(calendar_id)
    entry = calendar.entry_at(day, moment)
    if entry is None:
        return CalendarDay(
            calendar_id=calendar_id,
            is_open=is_open(calendar, day, moment),
            decided_by=DecidedBy.WEEKEND_RULE,
            reason=None,
            basis=None,
            revision=None,
            recorded_from=None,
            sources=(),
        )
    return CalendarDay(
        calendar_id=calendar_id,
        is_open=is_open(calendar, day, moment),
        decided_by=DecidedBy.ENTRY,
        reason=entry.reason,
        basis=entry.basis,
        revision=entry.revision,
        recorded_from=entry.recorded_from,
        sources=_sources(entry),
    )


def _sources(entry: Entry) -> tuple[Source, ...]:
    return tuple(
        Source(
            publisher=source.publisher,
            url=source.url,
            retrieved_at=source.fetched_at,
            snapshot_sha256=source.sha256,
        )
        for source in entry.sources
    )


def _revisions(release: Release, calendar_ids: Sequence[str], moment: datetime) -> dict[str, int]:
    return {
        calendar_id: release.calendar(calendar_id).revision_at(moment)
        for calendar_id in sorted(set(calendar_ids))
    }


# JSON helpers.


def _calendar_days(raw: Sequence[Mapping[str, Any]]) -> tuple[CalendarDay, ...]:
    return tuple(CalendarDay.from_json(calendar) for calendar in raw)


def _stamp(raw: Mapping[str, Any]) -> dict[str, Any]:
    """The fields every answer shares: as of, release, revisions and receipt."""
    receipt = raw.get("receipt")
    return {
        "as_of": _parse_instant(raw["as_of"]),
        "dataset_release": str(raw["dataset_release"]),
        "calendar_revisions": {
            str(key): int(value)
            for key, value in cast(Mapping[str, Any], raw["calendar_revisions"]).items()
        },
        "receipt": None if receipt is None else Receipt.from_json(receipt),
    }


def _stamp_json(
    as_of: datetime,
    dataset_release: str,
    calendar_revisions: Mapping[str, int],
    receipt: Receipt | None,
) -> Json:
    json: Json = {
        "as_of": _instant(as_of),
        "dataset_release": dataset_release,
        "calendar_revisions": dict(calendar_revisions),
    }
    if receipt is not None:
        json["receipt"] = receipt.to_json()
    return json


def _instant(moment: datetime) -> str:
    # The API writes UTC instants with a Z, as Java does.
    return moment.isoformat().replace("+00:00", "Z")


def _parse_instant(value: object) -> datetime:
    moment = datetime.fromisoformat(str(value))
    if moment.tzinfo is None:
        raise ValueError(f"Timestamp without a time zone: {value!r}")
    return moment
