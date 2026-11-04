"""PostgreSQL access for the pipeline. Parameterized SQL only; Flyway in api/ owns the schema."""

from collections.abc import Iterable, Iterator, Sequence
from contextlib import AbstractContextManager, contextmanager
from dataclasses import dataclass
from datetime import date, datetime
from typing import Protocol

import psycopg
from psycopg.rows import TupleRow, tuple_row
from psycopg.types.json import Jsonb
from pydantic import BaseModel

from zeitwide_pipeline.calendars import CalendarDefinition
from zeitwide_pipeline.diff import (
    CalendarYear,
    EntryKey,
    PendingCandidate,
    PublishedEntry,
    corroboration,
    covered_years,
    entry_key,
    plan_diff,
)
from zeitwide_pipeline.fetch import Fetched, Validators
from zeitwide_pipeline.models import Basis, Corroboration, Observation
from zeitwide_pipeline.schedule import iso_duration
from zeitwide_pipeline.sources import Source

MAX_ERROR_LENGTH = 1000


@dataclass(frozen=True, slots=True)
class RegisteredSource:
    db_id: int
    enabled: bool


@dataclass(frozen=True, slots=True)
class SourceState:
    validators: Validators
    last_parsed_sha256: str | None
    last_checked_at: datetime | None
    consecutive_failures: int
    suspended_at: datetime | None


@dataclass(frozen=True, slots=True)
class SourceHealth:
    last_success_at: datetime | None
    consecutive_failures: int
    suspended: bool


@dataclass(frozen=True, slots=True)
class ReviewQueue:
    pending: int
    oldest_created_at: datetime | None
    soonest_entry_date: date | None


@dataclass(frozen=True, slots=True)
class DiffOutcome:
    candidate_ids: tuple[int, ...]
    new_candidates: int
    conflicting: int


class PipelineStore(Protocol):
    """The pipeline's port to the database."""

    def transaction(self) -> AbstractContextManager[object]: ...

    def register_calendars(self, calendars: Iterable[CalendarDefinition]) -> None: ...

    def register_sources(self, sources: Iterable[Source]) -> dict[str, RegisteredSource]: ...

    def find_source(self, slug: str) -> RegisteredSource | None: ...

    def load_states(self) -> dict[str, SourceState]: ...

    def load_state(self, source_db_id: int) -> SourceState: ...

    def save_snapshot(
        self, source_db_id: int, sha256: str, object_key: str, fetched: Fetched
    ) -> int:
        """Returns the snapshot's ID, inserting it unless the same bytes are already stored."""
        ...

    def record_observations(
        self, snapshot_id: int, parser_version: str, observations: Sequence[Observation]
    ) -> dict[EntryKey, int]:
        """Stores observations once per snapshot and parser version; returns their IDs."""
        ...

    def record_diff(
        self,
        snapshot_id: int,
        parser_version: str,
        observations: Sequence[Observation],
        operator_calendars: frozenset[str] = frozenset(),
    ) -> DiffOutcome: ...

    def corroborate(self, candidate_ids: Sequence[int]) -> dict[int, Corroboration]: ...

    def record_success(
        self,
        source_db_id: int,
        checked_at: datetime,
        validators: Validators,
        parsed_sha256: str | None,
    ) -> None: ...

    def record_failure(
        self,
        source_db_id: int,
        checked_at: datetime,
        consecutive_failures: int,
        error: str,
        *,
        suspend: bool,
    ) -> None: ...

    def resume(self, slug: str) -> bool: ...

    def load_health(self) -> dict[str, SourceHealth]: ...

    def review_queue(self) -> ReviewQueue: ...


@contextmanager
def open_store(conninfo: str) -> Iterator["PostgresPipelineStore"]:
    """An empty ``conninfo`` uses the standard PG* environment variables."""
    with psycopg.connect(conninfo, autocommit=True, row_factory=tuple_row) as connection:
        yield PostgresPipelineStore(connection)


class PostgresPipelineStore:
    def __init__(self, connection: psycopg.Connection[TupleRow]) -> None:
        self._db = connection

    def transaction(self) -> AbstractContextManager[object]:
        return self._db.transaction()

    def register_calendars(self, calendars: Iterable[CalendarDefinition]) -> None:
        with self._db.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO calendar (id, name, kind, timezone, weekend_rule, closes_on_holidays,
                                      observance_rule, operating_hours)
                VALUES (%s, %s, %s, %s, %s, %s, %s, %s)
                ON CONFLICT (id) DO UPDATE SET
                    name = EXCLUDED.name,
                    kind = EXCLUDED.kind,
                    timezone = EXCLUDED.timezone,
                    weekend_rule = EXCLUDED.weekend_rule,
                    closes_on_holidays = EXCLUDED.closes_on_holidays,
                    observance_rule = EXCLUDED.observance_rule,
                    operating_hours = EXCLUDED.operating_hours
                """,
                [
                    (
                        calendar.id,
                        calendar.name,
                        calendar.kind.value,
                        calendar.timezone,
                        Jsonb(calendar.weekend_rule()),
                        calendar.closes_on_holidays,
                        _model_json(calendar.observance),
                        _model_json(calendar.operating_hours),
                    )
                    for calendar in calendars
                ],
            )

    def register_sources(self, sources: Iterable[Source]) -> dict[str, RegisteredSource]:
        registered: dict[str, RegisteredSource] = {}
        for source in sources:
            row = self._db.execute(
                """
                INSERT INTO source (slug, publisher, url, parser_name, schedule, role)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (slug) DO UPDATE SET
                    publisher = EXCLUDED.publisher,
                    url = EXCLUDED.url,
                    parser_name = EXCLUDED.parser_name,
                    schedule = EXCLUDED.schedule,
                    role = EXCLUDED.role
                RETURNING id, enabled
                """,
                (
                    source.id,
                    source.publisher,
                    source.url,
                    source.parser.name,
                    iso_duration(source.schedule),
                    source.role.value,
                ),
            ).fetchone()
            row = _required(row)
            registered[source.id] = RegisteredSource(db_id=_int(row[0]), enabled=bool(row[1]))
        return registered

    def find_source(self, slug: str) -> RegisteredSource | None:
        row = self._db.execute("SELECT id, enabled FROM source WHERE slug = %s", (slug,)).fetchone()
        if row is None:
            return None
        return RegisteredSource(db_id=_int(row[0]), enabled=bool(row[1]))

    def load_states(self) -> dict[str, SourceState]:
        rows = self._db.execute(
            """
            SELECT s.slug, st.etag, st.last_modified, st.last_parsed_sha256, st.last_checked_at,
                   st.consecutive_failures, st.suspended_at
            FROM source AS s JOIN source_state AS st ON st.source_id = s.id
            """
        ).fetchall()
        return {str(row[0]): _state(row[1:]) for row in rows}

    def load_state(self, source_db_id: int) -> SourceState:
        row = self._db.execute(
            """
            SELECT st.etag, st.last_modified, st.last_parsed_sha256, st.last_checked_at,
                   st.consecutive_failures, st.suspended_at
            FROM source_state AS st WHERE st.source_id = %s
            """,
            (source_db_id,),
        ).fetchone()
        if row is None:
            return SourceState(Validators(), None, None, 0, None)
        return _state(row)

    def save_snapshot(
        self, source_db_id: int, sha256: str, object_key: str, fetched: Fetched
    ) -> int:
        digest = bytes.fromhex(sha256)
        self._db.execute(
            """
            INSERT INTO source_snapshot
                (source_id, fetched_at, sha256, object_key, http_status, content_type, byte_size)
            VALUES (%s, %s, %s, %s, %s, %s, %s)
            ON CONFLICT (source_id, sha256) DO NOTHING
            """,
            (
                source_db_id,
                fetched.fetched_at,
                digest,
                object_key,
                fetched.http_status,
                fetched.content_type,
                len(fetched.content),
            ),
        )
        row = self._db.execute(
            "SELECT id FROM source_snapshot WHERE source_id = %s AND sha256 = %s",
            (source_db_id, digest),
        ).fetchone()
        return _int(_required(row)[0])

    def record_diff(
        self,
        snapshot_id: int,
        parser_version: str,
        observations: Sequence[Observation],
        operator_calendars: frozenset[str] = frozenset(),
    ) -> DiffOutcome:
        # Whole years, so the plan sees closures the snapshot stopped listing.
        years = covered_years(map(entry_key, observations))
        plan = plan_diff(
            observations, self._published(years), self._pending(years), operator_calendars
        )
        observation_ids = self.record_observations(
            snapshot_id, parser_version, [*observations, *plan.not_listed]
        )
        with self._db.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO candidate_change (calendar_id, entry_date, before_state, after_state)
                VALUES (%s, %s, %s, %s)
                ON CONFLICT (calendar_id, entry_date) WHERE status = 'pending' DO NOTHING
                """,
                [
                    (
                        candidate.calendar_id,
                        candidate.entry_date,
                        _published_json(candidate.before),
                        Jsonb(
                            {"is_open": candidate.after.is_open, "reason": candidate.after.reason}
                        ),
                    )
                    for candidate in plan.new_candidates
                ],
            )
        pending = self._pending(years)
        # A candidate approved or rejected meanwhile is no longer pending; skip it.
        links = [
            (pending[key].id, observation_ids[key])
            for key in map(entry_key, plan.supporting)
            if key in pending
        ]
        with self._db.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO candidate_observation (candidate_id, observation_id)
                VALUES (%s, %s)
                ON CONFLICT DO NOTHING
                """,
                links,
            )
        return DiffOutcome(
            candidate_ids=tuple(sorted({candidate_id for candidate_id, _ in links})),
            new_candidates=len(plan.new_candidates),
            conflicting=len(plan.conflicting),
        )

    def corroborate(self, candidate_ids: Sequence[int]) -> dict[int, Corroboration]:
        if not candidate_ids:
            return {}
        rows = self._db.execute(
            """
            SELECT co.candidate_id, count(DISTINCT snapshot.source_id)
            FROM candidate_observation AS co
            JOIN observation ON observation.id = co.observation_id
            JOIN source_snapshot AS snapshot ON snapshot.id = observation.snapshot_id
            WHERE co.candidate_id = ANY(%s)
            GROUP BY co.candidate_id
            """,
            (list(candidate_ids),),
        ).fetchall()
        counts = {_int(row[0]): _int(row[1]) for row in rows}
        statuses = {candidate_id: corroboration(count) for candidate_id, count in counts.items()}
        with self._db.cursor() as cursor:
            cursor.executemany(
                "UPDATE candidate_change SET corroboration = %s, source_count = %s WHERE id = %s",
                [
                    (statuses[candidate_id].value, count, candidate_id)
                    for candidate_id, count in counts.items()
                ],
            )
        return statuses

    def record_success(
        self,
        source_db_id: int,
        checked_at: datetime,
        validators: Validators,
        parsed_sha256: str | None,
    ) -> None:
        self._db.execute(
            """
            INSERT INTO source_state AS st (source_id, etag, last_modified, last_parsed_sha256,
                                            last_checked_at, last_success_at)
            VALUES (%(id)s, %(etag)s, %(modified)s, %(sha)s, %(at)s, %(at)s)
            ON CONFLICT (source_id) DO UPDATE SET
                etag = EXCLUDED.etag,
                last_modified = EXCLUDED.last_modified,
                last_parsed_sha256 = EXCLUDED.last_parsed_sha256,
                last_checked_at = EXCLUDED.last_checked_at,
                last_success_at = EXCLUDED.last_success_at,
                consecutive_failures = 0,
                last_error = NULL
            """,
            {
                "id": source_db_id,
                "etag": validators.etag,
                "modified": validators.last_modified,
                "sha": bytes.fromhex(parsed_sha256) if parsed_sha256 else None,
                "at": checked_at,
            },
        )

    def record_failure(
        self,
        source_db_id: int,
        checked_at: datetime,
        consecutive_failures: int,
        error: str,
        *,
        suspend: bool,
    ) -> None:
        self._db.execute(
            """
            INSERT INTO source_state AS st (source_id, last_checked_at, consecutive_failures,
                                            last_error, suspended_at)
            VALUES (%(id)s, %(at)s, %(failures)s, %(error)s, %(suspended)s)
            ON CONFLICT (source_id) DO UPDATE SET
                last_checked_at = EXCLUDED.last_checked_at,
                consecutive_failures = EXCLUDED.consecutive_failures,
                last_error = EXCLUDED.last_error,
                suspended_at = coalesce(st.suspended_at, EXCLUDED.suspended_at)
            """,
            {
                "id": source_db_id,
                "at": checked_at,
                "failures": consecutive_failures,
                "error": error[:MAX_ERROR_LENGTH],
                "suspended": checked_at if suspend else None,
            },
        )

    def resume(self, slug: str) -> bool:
        cursor = self._db.execute(
            """
            UPDATE source_state AS st
            SET suspended_at = NULL, consecutive_failures = 0
            FROM source AS s
            WHERE s.id = st.source_id AND s.slug = %s AND st.suspended_at IS NOT NULL
            """,
            (slug,),
        )
        return cursor.rowcount > 0

    def load_health(self) -> dict[str, SourceHealth]:
        """Every enabled source, including those never checked."""
        rows = self._db.execute(
            """
            SELECT s.slug, st.last_success_at, coalesce(st.consecutive_failures, 0),
                   st.suspended_at IS NOT NULL
            FROM source AS s LEFT JOIN source_state AS st ON st.source_id = s.id
            WHERE s.enabled
            """
        ).fetchall()
        return {
            str(row[0]): SourceHealth(
                last_success_at=row[1] if isinstance(row[1], datetime) else None,
                consecutive_failures=_int(row[2]),
                suspended=bool(row[3]),
            )
            for row in rows
        }

    def review_queue(self) -> ReviewQueue:
        row = _required(
            self._db.execute(
                """
                SELECT count(*), min(created_at), min(entry_date)
                FROM candidate_change WHERE status = 'pending'
                """
            ).fetchone()
        )
        return ReviewQueue(
            pending=_int(row[0]),
            oldest_created_at=row[1] if isinstance(row[1], datetime) else None,
            soonest_entry_date=row[2] if isinstance(row[2], date) else None,
        )

    def record_observations(
        self, snapshot_id: int, parser_version: str, observations: Sequence[Observation]
    ) -> dict[EntryKey, int]:
        with self._db.cursor() as cursor:
            cursor.executemany(
                """
                INSERT INTO observation
                    (snapshot_id, calendar_id, entry_date, is_open, reason, parser_version)
                VALUES (%s, %s, %s, %s, %s, %s)
                ON CONFLICT (snapshot_id, calendar_id, entry_date, parser_version) DO NOTHING
                """,
                [
                    (
                        snapshot_id,
                        observation.calendar_id,
                        observation.entry_date,
                        observation.is_open,
                        observation.reason,
                        parser_version,
                    )
                    for observation in observations
                ],
            )
        rows = self._db.execute(
            """
            SELECT id, calendar_id, entry_date FROM observation
            WHERE snapshot_id = %s AND parser_version = %s
            """,
            (snapshot_id, parser_version),
        ).fetchall()
        return {(str(row[1]), _date(row[2])): _int(row[0]) for row in rows}

    def _published(self, years: Iterable[CalendarYear]) -> dict[EntryKey, PublishedEntry]:
        rows = self._db.execute(
            """
            SELECT e.calendar_id, e.entry_date, e.is_open, e.reason, e.basis
            FROM calendar_entry AS e
            JOIN unnest(%s::text[], %s::int[]) AS covered (calendar_id, year)
              ON e.calendar_id = covered.calendar_id
             AND e.entry_date >= make_date(covered.year, 1, 1)
             AND e.entry_date < make_date(covered.year + 1, 1, 1)
            WHERE upper_inf(e.recorded_during)
            """,
            _unzip(years),
        ).fetchall()
        return {
            (str(row[0]), _date(row[1])): PublishedEntry(
                bool(row[2]), str(row[3]), Basis(str(row[4]))
            )
            for row in rows
        }

    def _pending(self, years: Iterable[CalendarYear]) -> dict[EntryKey, PendingCandidate]:
        rows = self._db.execute(
            """
            SELECT c.id, c.calendar_id, c.entry_date, (c.after_state ->> 'is_open')::boolean
            FROM candidate_change AS c
            JOIN unnest(%s::text[], %s::int[]) AS covered (calendar_id, year)
              ON c.calendar_id = covered.calendar_id
             AND c.entry_date >= make_date(covered.year, 1, 1)
             AND c.entry_date < make_date(covered.year + 1, 1, 1)
            WHERE c.status = 'pending'
            """,
            _unzip(years),
        ).fetchall()
        return {
            (str(row[1]), _date(row[2])): PendingCandidate(_int(row[0]), bool(row[3]))
            for row in rows
        }


def _state(row: Sequence[object]) -> SourceState:
    etag, last_modified, parsed, checked_at, failures, suspended_at = row
    return SourceState(
        validators=Validators(
            etag=str(etag) if etag is not None else None,
            last_modified=str(last_modified) if last_modified is not None else None,
        ),
        last_parsed_sha256=bytes(parsed).hex() if isinstance(parsed, bytes | memoryview) else None,
        last_checked_at=checked_at if isinstance(checked_at, datetime) else None,
        consecutive_failures=_int(failures),
        suspended_at=suspended_at if isinstance(suspended_at, datetime) else None,
    )


def _model_json(model: BaseModel | None) -> Jsonb | None:
    return None if model is None else Jsonb(model.model_dump(mode="json", exclude_none=True))


def _published_json(entry: PublishedEntry | None) -> Jsonb | None:
    if entry is None:
        return None
    return Jsonb({"is_open": entry.is_open, "reason": entry.reason, "basis": entry.basis})


def _unzip(years: Iterable[CalendarYear]) -> tuple[list[str], list[int]]:
    pairs = sorted(years)
    return [calendar_id for calendar_id, _ in pairs], [year for _, year in pairs]


def _required(row: TupleRow | None) -> TupleRow:
    if row is None:
        raise LookupError("Expected a row from a statement that always returns one")
    return row


def _int(value: object) -> int:
    if not isinstance(value, int):
        raise TypeError(f"Expected an integer column, got {type(value).__name__}")
    return value


def _date(value: object) -> date:
    if not isinstance(value, date):
        raise TypeError(f"Expected a date column, got {type(value).__name__}")
    return value


def run_cleanup(conninfo: str) -> list[tuple[str, int]]:
    """Rolls usage up to yesterday, then deletes rows past their 30 days."""
    with psycopg.connect(conninfo, autocommit=True, row_factory=tuple_row) as connection:
        connection.execute("SELECT roll_up_usage(date_trunc('day', now(), 'UTC'))")
        rows = connection.execute(
            "SELECT table_name, deleted_rows FROM cleanup_old_rows()"
        ).fetchall()
    return [(str(row[0]), _int(row[1])) for row in rows]
