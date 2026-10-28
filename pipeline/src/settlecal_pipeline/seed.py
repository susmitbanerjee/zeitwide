"""Seed history: the first published versions of each calendar, from the sources themselves.

The seed fetches every source once, stores the snapshots and observations as a normal
check would, then publishes each closure in the chosen years directly, without a review,
because there is nothing yet to compare it with. Dates some source disagrees on are left
for the normal review flow. Running the seed again adds nothing for dates that already
have an entry.
"""

import hashlib
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass, field, replace
from datetime import date, datetime

import psycopg
import structlog
from psycopg.rows import TupleRow

from settlecal_pipeline.calendars import CalendarDefinition
from settlecal_pipeline.clock import Clock
from settlecal_pipeline.db import PipelineStore, RegisteredSource
from settlecal_pipeline.dryrun import archive_capture_time, fetch_snapshot, snapshot_meta
from settlecal_pipeline.fetch import Fetcher
from settlecal_pipeline.publish import EntryState, basis_for
from settlecal_pipeline.sources import Source, parse_snapshot
from settlecal_pipeline.storage import SnapshotStore, snapshot_key

log = structlog.get_logger(__name__)


@dataclass(frozen=True, slots=True)
class Evidence:
    source_id: str
    snapshot_id: int
    is_open: bool
    reason: str


@dataclass(frozen=True, slots=True)
class SeedEntry:
    entry_date: date
    state: EntryState
    snapshot_ids: tuple[int, ...]


@dataclass
class SeedResult:
    published: dict[str, int] = field(default_factory=dict)
    conflicts: list[tuple[str, date]] = field(default_factory=list)


def plan_seed(
    calendar: CalendarDefinition,
    evidence: Mapping[date, Sequence[Evidence]],
    years: frozenset[int],
    existing: frozenset[date],
) -> tuple[list[SeedEntry], list[date]]:
    """Closures to publish for one calendar, and the dates sources disagree on. Pure."""
    entries: list[SeedEntry] = []
    conflicts: list[date] = []
    for entry_date in sorted(evidence):
        rows = evidence[entry_date]
        if entry_date.year not in years or entry_date in existing:
            continue
        if all(row.is_open for row in rows):
            continue
        if any(row.is_open for row in rows):
            conflicts.append(entry_date)
            continue
        operator_rows = [row for row in rows if row.source_id in calendar.operator_sources]
        reason = (operator_rows or list(rows))[0].reason
        basis = basis_for((row.source_id for row in rows), calendar.operator_sources)
        entries.append(
            SeedEntry(
                entry_date,
                EntryState(is_open=False, reason=reason, basis=basis),
                tuple(sorted({row.snapshot_id for row in rows})),
            )
        )
    return entries, conflicts


class PostgresSeedStore:
    def __init__(self, connection: psycopg.Connection[TupleRow]) -> None:
        self._db = connection

    def existing_dates(self, calendar_id: str) -> frozenset[date]:
        rows = self._db.execute(
            """
            SELECT entry_date FROM calendar_entry
            WHERE calendar_id = %s AND upper_inf(recorded_during)
            """,
            (calendar_id,),
        ).fetchall()
        return frozenset(row[0] for row in rows if isinstance(row[0], date))

    def publish_first_versions(
        self, calendar_id: str, entries: Sequence[SeedEntry], now: datetime
    ) -> None:
        """One transaction per calendar: one revision for the whole seed."""
        if not entries:
            return
        with self._db.transaction():
            row = self._db.execute(
                """
                UPDATE calendar SET current_revision = current_revision + 1
                WHERE id = %s RETURNING current_revision
                """,
                (calendar_id,),
            ).fetchone()
            if row is None:
                raise LookupError(f"Unknown calendar {calendar_id}")
            revision = int(row[0])
            for entry in entries:
                published = self._db.execute(
                    "SELECT id FROM publish_entry_version(%s, %s, %s, %s, %s, %s, %s)",
                    (
                        calendar_id,
                        entry.entry_date,
                        entry.state.is_open,
                        entry.state.reason,
                        entry.state.basis.value,
                        revision,
                        now,
                    ),
                ).fetchone()
                if published is None:
                    raise LookupError("publish_entry_version returned no row")
                with self._db.cursor() as cursor:
                    cursor.executemany(
                        "INSERT INTO entry_source (entry_id, snapshot_id) VALUES (%s, %s)",
                        [(published[0], snapshot_id) for snapshot_id in entry.snapshot_ids],
                    )


@dataclass(frozen=True)
class Seeder:
    fetcher: Fetcher
    snapshots: SnapshotStore
    calendars: Mapping[str, CalendarDefinition]
    clock: Clock

    def run(
        self,
        sources: Iterable[Source],
        store: PipelineStore,
        seed_store: PostgresSeedStore,
        years: frozenset[int],
        also: Mapping[str, Sequence[str]] | None = None,
    ) -> SeedResult:
        """``also`` adds earlier editions by URL, such as an Internet Archive capture of the
        Bundesbank's previous year, for sources whose live page shows only one year."""
        store.register_calendars(self.calendars.values())
        source_list = list(sources)
        registered = store.register_sources(source_list)
        evidence: dict[str, dict[date, list[Evidence]]] = {}
        for source in source_list:
            urls: list[str | None] = [None, *(also or {}).get(source.id, [])]
            for url in urls:
                for calendar_id, entry_date, row in self._collect(
                    source, registered[source.id], store, url
                ):
                    evidence.setdefault(calendar_id, {}).setdefault(entry_date, []).append(row)
        result = SeedResult()
        now = self.clock()
        for calendar_id, by_date in sorted(evidence.items()):
            calendar = self.calendars[calendar_id]
            entries, conflicts = plan_seed(
                calendar, by_date, years, seed_store.existing_dates(calendar_id)
            )
            seed_store.publish_first_versions(calendar_id, entries, now)
            result.published[calendar_id] = len(entries)
            result.conflicts.extend((calendar_id, conflict) for conflict in conflicts)
            log.info(
                "calendar_seeded",
                calendar=calendar_id,
                entries=len(entries),
                conflicts=len(conflicts),
            )
        return result

    def _collect(
        self,
        source: Source,
        registered: RegisteredSource,
        store: PipelineStore,
        url: str | None = None,
    ) -> list[tuple[str, date, Evidence]]:
        fetched = fetch_snapshot(self.fetcher, source, url)
        sha256 = hashlib.sha256(fetched.content).hexdigest()
        key = snapshot_key(sha256)
        self.snapshots.put(key, fetched.content, fetched.content_type)
        snapshot_id = store.save_snapshot(registered.db_id, sha256, key, fetched)
        meta = snapshot_meta(source, fetched, url)
        captured = archive_capture_time(url) if url else None
        if captured is not None:
            meta = replace(meta, fetched_at=captured)
        observations = parse_snapshot(source.parser, fetched.content, meta)
        with store.transaction():
            store.record_observations(snapshot_id, source.parser.version, observations)
            if url is None:
                # The next scheduled check then sees these bytes as already processed.
                store.record_success(
                    registered.db_id, fetched.fetched_at, fetched.validators, sha256
                )
        return [
            (o.calendar_id, o.entry_date, Evidence(source.id, snapshot_id, o.is_open, o.reason))
            for o in observations
        ]
