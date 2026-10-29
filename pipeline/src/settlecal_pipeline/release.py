"""Dataset releases: the JSON in ``dataset/schema/release.schema.json``.

A release is everything SettleCal had recorded by one instant (``as_of``): every entry
version, with when it was believed, so the open library can answer as-of questions too.
Entries are bitemporal, so exporting the same ``as_of`` again gives the same bytes, which
is how a failed upload or pull request is retried without cutting a new release.
"""

import hashlib
import json
import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import UTC, date, datetime

import psycopg
from psycopg.rows import TupleRow
from psycopg.types.json import Jsonb

SCHEMA_VERSION = 2
LICENSE = "CC BY-NC 4.0"
_RELEASE_ID = re.compile(r"^(?P<day>\d{4}\.\d{2}\.\d{2})\.(?P<n>\d+)$")


@dataclass(frozen=True, slots=True)
class ReleaseRecord:
    id: str
    as_of: datetime
    calendar_revisions: Mapping[str, int]
    export_sha256: str


@dataclass(frozen=True, slots=True)
class ReleaseExport:
    id: str
    as_of: datetime
    calendar_revisions: Mapping[str, int]
    content: bytes

    @property
    def sha256(self) -> str:
        return hashlib.sha256(self.content).hexdigest()

    @property
    def object_key(self) -> str:
        return release_object_key(self.id)


def release_object_key(release_id: str) -> str:
    return f"releases/{release_id}.json"


def next_release_id(day: date, existing: Sequence[str]) -> str:
    """``YYYY.MM.DD.N``, where N counts that day's releases from 1."""
    prefix = day.strftime("%Y.%m.%d")
    taken = [
        int(match["n"])
        for release_id in existing
        if (match := _RELEASE_ID.match(release_id)) and match["day"] == prefix
    ]
    return f"{prefix}.{max(taken, default=0) + 1}"


def revisions_changed(current: Mapping[str, int], latest: ReleaseRecord | None) -> bool:
    return latest is None or dict(current) != dict(latest.calendar_revisions)


def render(document: Mapping[str, object]) -> bytes:
    """Stable bytes for the same document: sorted keys, fixed indentation, one newline."""
    return (json.dumps(document, indent=2, sort_keys=True, ensure_ascii=False) + "\n").encode()


class PostgresReleaseStore:
    """Reads entries as of an instant and records releases."""

    def __init__(self, connection: psycopg.Connection[TupleRow]) -> None:
        self._db = connection

    def latest(self) -> ReleaseRecord | None:
        row = self._db.execute(
            """
            SELECT id, as_of, calendar_revisions, export_sha256 FROM dataset_release
            ORDER BY as_of DESC, created_at DESC LIMIT 1
            """
        ).fetchone()
        if row is None:
            return None
        revisions = row[2]
        if not isinstance(revisions, dict) or not isinstance(row[1], datetime):
            raise TypeError("Unexpected dataset_release row")
        return ReleaseRecord(
            id=str(row[0]),
            as_of=row[1],
            calendar_revisions={str(k): int(v) for k, v in revisions.items()},
            export_sha256=bytes(row[3]).hex(),
        )

    def release_ids_on(self, day: date) -> list[str]:
        rows = self._db.execute(
            "SELECT id FROM dataset_release WHERE id LIKE %s",
            (day.strftime("%Y.%m.%d") + ".%",),
        ).fetchall()
        return [str(row[0]) for row in rows]

    def revisions(self, as_of: datetime) -> dict[str, int]:
        """Each calendar's revision as recorded at ``as_of``; 0 before its first entry."""
        rows = self._db.execute(
            """
            SELECT c.id, coalesce(max(e.revision), 0)
            FROM calendar AS c
            LEFT JOIN calendar_entry AS e
                   ON e.calendar_id = c.id AND lower(e.recorded_during) <= %s
            GROUP BY c.id ORDER BY c.id
            """,
            (as_of,),
        ).fetchall()
        return {str(row[0]): int(row[1]) for row in rows}

    def export(self, release_id: str, as_of: datetime) -> ReleaseExport:
        revisions = self.revisions(as_of)
        entries = self._entries(as_of)
        calendars = [
            {
                **calendar,
                "revision": revisions[str(calendar["id"])],
                "entries": entries.get(str(calendar["id"]), []),
            }
            for calendar in self._calendars()
        ]
        document = {
            "schema_version": SCHEMA_VERSION,
            "release": release_id,
            "as_of": _utc_iso(as_of),
            "license": LICENSE,
            "calendars": calendars,
        }
        return ReleaseExport(release_id, as_of, revisions, render(document))

    def record(self, export: ReleaseExport) -> None:
        self._db.execute(
            """
            INSERT INTO dataset_release (id, as_of, calendar_revisions, export_sha256)
            VALUES (%s, %s, %s, %s)
            """,
            (
                export.id,
                export.as_of,
                Jsonb(dict(export.calendar_revisions)),
                bytes.fromhex(export.sha256),
            ),
        )

    def _calendars(self) -> list[dict[str, object]]:
        rows = self._db.execute(
            """
            SELECT id, name, kind, timezone, weekend_rule, closes_on_holidays,
                   observance_rule, operating_hours
            FROM calendar ORDER BY id
            """
        ).fetchall()
        return [
            {
                "id": str(row[0]),
                "name": str(row[1]),
                "kind": str(row[2]),
                "timezone": str(row[3]),
                "weekend": _weekend(row[4]),
                "closes_on_holidays": bool(row[5]),
                "observance": row[6],
                "operating_hours": row[7],
            }
            for row in rows
        ]

    def _entries(self, as_of: datetime) -> dict[str, list[dict[str, object]]]:
        rows = self._db.execute(
            """
            SELECT e.id, e.calendar_id, e.entry_date, e.is_open, e.reason, e.basis, e.revision,
                   lower(e.recorded_during),
                   -- A version replaced after as_of was still current at as_of.
                   CASE WHEN upper(e.recorded_during) <= %(as_of)s
                        THEN upper(e.recorded_during) END
            FROM calendar_entry AS e
            WHERE lower(e.recorded_during) <= %(as_of)s
            ORDER BY e.calendar_id, e.entry_date, lower(e.recorded_during)
            """,
            {"as_of": as_of},
        ).fetchall()
        sources = self._sources([int(row[0]) for row in rows])
        entries: dict[str, list[dict[str, object]]] = {}
        for row in rows:
            entries.setdefault(str(row[1]), []).append(
                {
                    "date": _date(row[2]).isoformat(),
                    "is_open": bool(row[3]),
                    "reason": str(row[4]),
                    "basis": str(row[5]),
                    "revision": int(row[6]),
                    "recorded_from": _utc_iso(row[7]),
                    "recorded_to": None if row[8] is None else _utc_iso(row[8]),
                    "sources": sources.get(int(row[0]), []),
                }
            )
        return entries

    def _sources(self, entry_ids: list[int]) -> dict[int, list[dict[str, object]]]:
        rows = self._db.execute(
            """
            SELECT es.entry_id, s.slug, s.publisher, s.url, ss.sha256, ss.fetched_at
            FROM entry_source AS es
            JOIN source_snapshot AS ss ON ss.id = es.snapshot_id
            JOIN source AS s ON s.id = ss.source_id
            WHERE es.entry_id = ANY(%s)
            ORDER BY es.entry_id, s.slug, ss.fetched_at
            """,
            (entry_ids,),
        ).fetchall()
        sources: dict[int, list[dict[str, object]]] = {}
        for row in rows:
            sources.setdefault(int(row[0]), []).append(
                {
                    "source_id": str(row[1]),
                    "publisher": str(row[2]),
                    "url": str(row[3]),
                    "sha256": bytes(row[4]).hex(),
                    "fetched_at": _utc_iso(row[5]),
                }
            )
        return sources


def _mapping(value: object) -> Mapping[str, object]:
    if not isinstance(value, dict):
        raise TypeError(f"Expected a JSON object, got {type(value).__name__}")
    return value


def _date(value: object) -> date:
    if not isinstance(value, date):
        raise TypeError(f"Expected a date column, got {type(value).__name__}")
    return value


def _utc_iso(value: object) -> str:
    """The database session's time zone must not change the bytes."""
    if not isinstance(value, datetime) or value.tzinfo is None:
        raise TypeError(f"Expected an aware timestamp, got {value!r}")
    return value.astimezone(UTC).isoformat()


def _weekend(rule: object) -> list[str]:
    days = _mapping(rule).get("days", [])
    if not isinstance(days, list):
        raise TypeError("weekend_rule.days must be a list")
    return [str(day) for day in days]
