"""Review and publish candidate changes in PostgreSQL.

Approving a candidate publishes it in the same transaction: the new entry version, its
evidence (``entry_source``), the calendar revision, a ``change_event`` and its outbox row.
The candidate row is locked first and ``change_event.candidate_id`` is unique, so
publishing the same candidate twice changes nothing.
"""

from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, datetime
from enum import StrEnum

import psycopg
from psycopg.rows import TupleRow, tuple_row
from psycopg.types.json import Jsonb

from zeitwide_pipeline.calendars import CalendarDefinition
from zeitwide_pipeline.models import Basis
from zeitwide_pipeline.publish import (
    URGENT_NOTICE_DAYS,
    ChangeEvent,
    EntryState,
    basis_for,
    change_type,
    notice_days,
)

DEFAULT_LIST_LIMIT = 50
MAX_LIST_LIMIT = 500
MAX_REVIEWER_LENGTH = 255
MAX_REASON_LENGTH = 1000


class ReviewError(Exception):
    """The candidate can't be reviewed: unknown, or no longer in the right state."""


class PublishResult(StrEnum):
    PUBLISHED = "published"
    ALREADY_PUBLISHED = "already_published"


@dataclass(frozen=True, slots=True)
class Review:
    """Who decided, and why. The web review screen will pass the reviewer's sign-in subject."""

    reviewer: str
    reason: str

    def __post_init__(self) -> None:
        reviewer, reason = self.reviewer.strip(), self.reason.strip()
        if not reviewer or len(reviewer) > MAX_REVIEWER_LENGTH:
            raise ValueError(f"A reviewer is 1 to {MAX_REVIEWER_LENGTH} characters")
        if not reason or len(reason) > MAX_REASON_LENGTH:
            raise ValueError(f"A reason is 1 to {MAX_REASON_LENGTH} characters")


@dataclass(frozen=True, slots=True)
class CandidateSummary:
    id: int
    calendar_id: str
    entry_date: date
    before: Mapping[str, object] | None
    after: Mapping[str, object]
    corroboration: str
    source_count: int
    notice_days: int
    status: str


@dataclass(frozen=True, slots=True)
class SupportingSnapshot:
    source_id: str
    source_url: str
    fetched_at: datetime
    sha256: str
    object_key: str
    is_open: bool
    reason: str


@dataclass(frozen=True, slots=True)
class CandidateDetail:
    summary: CandidateSummary
    current: EntryState | None
    snapshots: tuple[SupportingSnapshot, ...]
    reviewer: str | None
    review_reason: str | None


@contextmanager
def open_review_store(
    conninfo: str, calendars: Mapping[str, CalendarDefinition]
) -> Iterator["PostgresReviewStore"]:
    with psycopg.connect(conninfo, autocommit=True, row_factory=tuple_row) as connection:
        yield PostgresReviewStore(connection, calendars)


class PostgresReviewStore:
    def __init__(
        self,
        connection: psycopg.Connection[TupleRow],
        calendars: Mapping[str, CalendarDefinition],
    ) -> None:
        self._db = connection
        self._calendars = calendars

    def list_candidates(
        self, today: date, *, status: str = "pending", limit: int = DEFAULT_LIST_LIMIT
    ) -> list[CandidateSummary]:
        """Urgent first (under 14 days' notice), then by date."""
        rows = self._db.execute(
            """
            SELECT c.id, c.calendar_id, c.entry_date, c.before_state, c.after_state,
                   c.corroboration, c.source_count, c.entry_date - %(today)s, c.status
            FROM candidate_change AS c
            WHERE c.status = %(status)s
            ORDER BY (c.entry_date - %(today)s) < %(urgent)s DESC, c.entry_date, c.id
            LIMIT %(limit)s
            """,
            {
                "status": status,
                "today": today,
                "urgent": URGENT_NOTICE_DAYS,
                "limit": max(1, min(limit, MAX_LIST_LIMIT)),
            },
        ).fetchall()
        return [_summary(row) for row in rows]

    def candidate(self, candidate_id: int, today: date) -> CandidateDetail:
        row = self._db.execute(
            """
            SELECT c.id, c.calendar_id, c.entry_date, c.before_state, c.after_state,
                   c.corroboration, c.source_count, c.entry_date - %(today)s, c.status,
                   c.reviewer_subject, c.review_reason
            FROM candidate_change AS c WHERE c.id = %(id)s
            """,
            {"id": candidate_id, "today": today},
        ).fetchone()
        if row is None:
            raise ReviewError(f"No candidate {candidate_id}")
        summary = _summary(row)
        return CandidateDetail(
            summary=summary,
            current=self._current(summary.calendar_id, summary.entry_date),
            snapshots=self._snapshots(candidate_id),
            reviewer=_optional_text(row[-2]),
            review_reason=_optional_text(row[-1]),
        )

    def approve(self, candidate_id: int, review: Review, now: datetime) -> PublishResult:
        """Approves a pending candidate and publishes it, all in one transaction."""
        with self._db.transaction():
            status = self._lock(candidate_id)
            if status == "published":
                return PublishResult.ALREADY_PUBLISHED
            if status != "pending":
                raise ReviewError(f"Candidate {candidate_id} is {status}, not pending")
            self._db.execute(
                """
                UPDATE candidate_change
                SET status = 'approved', reviewer_subject = %s, review_reason = %s,
                    reviewed_at = %s
                WHERE id = %s
                """,
                (review.reviewer.strip(), review.reason.strip(), now, candidate_id),
            )
            self._publish_locked(candidate_id, now)
        return PublishResult.PUBLISHED

    def reject(self, candidate_id: int, review: Review, now: datetime) -> None:
        with self._db.transaction():
            status = self._lock(candidate_id)
            if status != "pending":
                raise ReviewError(f"Candidate {candidate_id} is {status}, not pending")
            self._db.execute(
                """
                UPDATE candidate_change
                SET status = 'rejected', reviewer_subject = %s, review_reason = %s,
                    reviewed_at = %s
                WHERE id = %s
                """,
                (review.reviewer.strip(), review.reason.strip(), now, candidate_id),
            )

    def publish(self, candidate_id: int, now: datetime) -> PublishResult:
        """Publishes a candidate approved elsewhere (the web review screen)."""
        with self._db.transaction():
            status = self._lock(candidate_id)
            if status == "published":
                return PublishResult.ALREADY_PUBLISHED
            if status != "approved":
                raise ReviewError(f"Candidate {candidate_id} is {status}, not approved")
            self._publish_locked(candidate_id, now)
        return PublishResult.PUBLISHED

    def approved_ids(self) -> list[int]:
        rows = self._db.execute(
            "SELECT id FROM candidate_change WHERE status = 'approved' ORDER BY id"
        ).fetchall()
        return [int(row[0]) for row in rows]

    def _lock(self, candidate_id: int) -> str:
        row = self._db.execute(
            "SELECT status FROM candidate_change WHERE id = %s FOR UPDATE", (candidate_id,)
        ).fetchone()
        if row is None:
            raise ReviewError(f"No candidate {candidate_id}")
        return str(row[0])

    def _publish_locked(self, candidate_id: int, now: datetime) -> None:
        calendar_id, entry_date, after_state = _required(
            self._db.execute(
                "SELECT calendar_id, entry_date, after_state FROM candidate_change WHERE id = %s",
                (candidate_id,),
            ).fetchone()
        )
        calendar_id, entry_date = str(calendar_id), _date(entry_date)
        snapshots = self._snapshots(candidate_id)
        calendar = self._calendars.get(calendar_id)
        operators = calendar.operator_sources if calendar else ()
        state = _mapping(after_state)
        after = EntryState(
            is_open=bool(state["is_open"]),
            reason=str(state["reason"]),
            basis=basis_for((s.source_id for s in snapshots), operators),
        )
        before = self._current(calendar_id, entry_date)
        change = change_type(before, after)
        revision = self._bump_revision(calendar_id)
        entry_id = _int(
            _required(
                self._db.execute(
                    "SELECT id FROM publish_entry_version(%s, %s, %s, %s, %s, %s, %s)",
                    (
                        calendar_id,
                        entry_date,
                        after.is_open,
                        after.reason,
                        after.basis.value,
                        revision,
                        now,
                    ),
                ).fetchone()
            )[0]
        )
        self._link_evidence(entry_id, candidate_id)
        event_id = _int(
            _required(
                self._db.execute(
                    """
                    INSERT INTO change_event (change_type, calendar_id, entry_date, entry_id,
                                              before_state, after_state, notice_days,
                                              published_at, candidate_id)
                    VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)
                    RETURNING id
                    """,
                    (
                        change.value,
                        calendar_id,
                        entry_date,
                        entry_id,
                        Jsonb(before.to_json()) if before else None,
                        Jsonb(after.to_json()),
                        notice_days(entry_date, now.date()),
                        now,
                        candidate_id,
                    ),
                ).fetchone()
            )[0]
        )
        event = ChangeEvent(event_id, change, calendar_id, entry_date, before, after, revision, now)
        self._db.execute(
            "INSERT INTO outbox (change_event_id, event_type, payload) VALUES (%s, %s, %s)",
            (event_id, change.value, Jsonb(event.payload())),
        )
        self._db.execute(
            "UPDATE candidate_change SET status = 'published' WHERE id = %s", (candidate_id,)
        )

    def _bump_revision(self, calendar_id: str) -> int:
        row = self._db.execute(
            """
            UPDATE calendar SET current_revision = current_revision + 1
            WHERE id = %s RETURNING current_revision
            """,
            (calendar_id,),
        ).fetchone()
        if row is None:
            raise ReviewError(f"Unknown calendar {calendar_id}")
        return _int(row[0])

    def _link_evidence(self, entry_id: int, candidate_id: int) -> None:
        self._db.execute(
            """
            INSERT INTO entry_source (entry_id, snapshot_id)
            SELECT DISTINCT %s::bigint, o.snapshot_id
            FROM candidate_observation AS co JOIN observation AS o ON o.id = co.observation_id
            WHERE co.candidate_id = %s
            ON CONFLICT DO NOTHING
            """,
            (entry_id, candidate_id),
        )

    def _current(self, calendar_id: str, entry_date: date) -> EntryState | None:
        row = self._db.execute(
            """
            SELECT is_open, reason, basis FROM calendar_entry
            WHERE calendar_id = %s AND entry_date = %s AND upper_inf(recorded_during)
            """,
            (calendar_id, entry_date),
        ).fetchone()
        if row is None:
            return None
        return EntryState(bool(row[0]), str(row[1]), Basis(str(row[2])))

    def _snapshots(self, candidate_id: int) -> tuple[SupportingSnapshot, ...]:
        rows = self._db.execute(
            """
            SELECT s.slug, s.url, ss.fetched_at, ss.sha256, ss.object_key, o.is_open, o.reason
            FROM candidate_observation AS co
            JOIN observation AS o ON o.id = co.observation_id
            JOIN source_snapshot AS ss ON ss.id = o.snapshot_id
            JOIN source AS s ON s.id = ss.source_id
            WHERE co.candidate_id = %s
            ORDER BY s.slug, ss.fetched_at
            """,
            (candidate_id,),
        ).fetchall()
        return tuple(
            SupportingSnapshot(
                source_id=str(row[0]),
                source_url=str(row[1]),
                fetched_at=_datetime(row[2]),
                sha256=bytes(row[3]).hex(),
                object_key=str(row[4]),
                is_open=bool(row[5]),
                reason=str(row[6]),
            )
            for row in rows
        )


def _summary(row: Sequence[object]) -> CandidateSummary:
    before = row[3]
    return CandidateSummary(
        id=_int(row[0]),
        calendar_id=str(row[1]),
        entry_date=_date(row[2]),
        before=_mapping(before) if before is not None else None,
        after=_mapping(row[4]),
        corroboration=str(row[5]),
        source_count=_int(row[6]),
        notice_days=_int(row[7]),
        status=str(row[8]),
    )


def _required(row: TupleRow | None) -> TupleRow:
    if row is None:
        raise LookupError("Expected a row from a statement that always returns one")
    return row


def _mapping(value: object) -> Mapping[str, object]:
    if not isinstance(value, dict):
        raise TypeError(f"Expected a JSON object, got {type(value).__name__}")
    return value


def _optional_text(value: object) -> str | None:
    return None if value is None else str(value)


def _int(value: object) -> int:
    if not isinstance(value, int):
        raise TypeError(f"Expected an integer column, got {type(value).__name__}")
    return value


def _date(value: object) -> date:
    if not isinstance(value, date):
        raise TypeError(f"Expected a date column, got {type(value).__name__}")
    return value


def _datetime(value: object) -> datetime:
    if not isinstance(value, datetime):
        raise TypeError(f"Expected a timestamp column, got {type(value).__name__}")
    return value
