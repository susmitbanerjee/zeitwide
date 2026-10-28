"""Fetch and parse real sources without touching the database or storage.

``dry-run`` proves every parser still reads its live page; ``save-fixture`` stores a real
snapshot for golden tests.
"""

import gzip
import hashlib
import json
import re
from collections import Counter
from collections.abc import Iterable, Mapping
from dataclasses import dataclass, replace
from datetime import UTC, date, datetime
from pathlib import Path

from settlecal_pipeline.calendars import CalendarDefinition
from settlecal_pipeline.clock import Clock
from settlecal_pipeline.fetch import Fetched, Fetcher, FetchError, Unchanged, Validators
from settlecal_pipeline.models import Observation, SnapshotMeta
from settlecal_pipeline.retrieve import fetch_source
from settlecal_pipeline.sources import ParseError, Source, parse_snapshot

_ARCHIVE_CAPTURE = re.compile(r"^https://web\.archive\.org/web/(?P<stamp>\d{14})id_/")
ARCHIVE_STAMP_FORMAT = "%Y%m%d%H%M%S"


def archive_capture_time(url: str) -> datetime | None:
    """When an Internet Archive ``id_`` capture URL says the page was captured."""
    match = _ARCHIVE_CAPTURE.match(url)
    if match is None:
        return None
    return datetime.strptime(match["stamp"], ARCHIVE_STAMP_FORMAT).replace(tzinfo=UTC)


@dataclass(frozen=True, slots=True)
class DryRunResult:
    source_id: str
    ok: bool
    observations: int = 0
    by_calendar: Mapping[str, int] | None = None
    first: date | None = None
    last: date | None = None
    error: str | None = None

    def to_json(self) -> str:
        return json.dumps(
            {
                "source_id": self.source_id,
                "ok": self.ok,
                "observations": self.observations,
                "by_calendar": dict(self.by_calendar or {}),
                "first": self.first.isoformat() if self.first else None,
                "last": self.last.isoformat() if self.last else None,
                "error": self.error,
            }
        )


def fetch_snapshot(fetcher: Fetcher, source: Source, url: str | None = None) -> Fetched:
    """Fetches without validators, so the server always sends the full body."""
    if url is None:
        result = fetch_source(fetcher, source, Validators())
    else:
        result = fetcher.fetch(url, source.content_types, Validators())
    if isinstance(result, Unchanged):
        raise FetchError(f"{source.id} answered 304 to an unconditional request")
    return result


def snapshot_meta(source: Source, fetched: Fetched, url: str | None = None) -> SnapshotMeta:
    return SnapshotMeta(
        source_id=source.id,
        url=url or fetched.url or source.url,
        fetched_at=fetched.fetched_at,
        content_type=fetched.content_type,
        sha256=hashlib.sha256(fetched.content).hexdigest(),
    )


def dry_run(
    sources: Iterable[Source], fetcher: Fetcher, calendars: Mapping[str, CalendarDefinition]
) -> list[DryRunResult]:
    results = []
    for source in sources:
        try:
            fetched = fetch_snapshot(fetcher, source)
            observations = parse_snapshot(
                source.parser, fetched.content, snapshot_meta(source, fetched)
            )
            _check_calendars(source, observations, calendars)
        except (FetchError, ParseError) as error:
            results.append(
                DryRunResult(source.id, ok=False, error=f"{type(error).__name__}: {error}")
            )
            continue
        dates = [o.entry_date for o in observations]
        results.append(
            DryRunResult(
                source.id,
                ok=True,
                observations=len(observations),
                by_calendar=dict(sorted(Counter(o.calendar_id for o in observations).items())),
                first=min(dates),
                last=max(dates),
            )
        )
    return results


def _check_calendars(
    source: Source, observations: list[Observation], calendars: Mapping[str, CalendarDefinition]
) -> None:
    unknown = {o.calendar_id for o in observations} - calendars.keys()
    if unknown:
        raise ParseError(f"{source.id} emits calendars missing from the catalog: {sorted(unknown)}")


def save_fixture(  # noqa: PLR0913 - keyword-only options for the CLI
    fetcher: Fetcher,
    source: Source,
    name: str,
    folder: Path,
    *,
    clock: Clock,
    url: str | None = None,
) -> Path:
    """Writes ``<name>.gz`` (the exact bytes) and ``<name>.meta.json`` under ``folder``.

    ``url`` overrides the source URL, for example an Internet Archive ``id_`` capture of
    an earlier version of the page.
    """
    fetched = fetch_snapshot(fetcher, source, url)
    meta = snapshot_meta(source, fetched, url)
    captured = archive_capture_time(url) if url else None
    if captured is not None:
        # The page is as it was then, which is what a date-aware parser needs to know.
        meta = replace(meta, fetched_at=captured)
    target = folder / source.id
    target.mkdir(parents=True, exist_ok=True)
    # mtime=0 keeps the archive identical for identical bytes.
    (target / f"{name}.gz").write_bytes(gzip.compress(fetched.content, mtime=0))
    (target / f"{name}.meta.json").write_text(
        json.dumps(
            {
                "url": meta.url,
                "fetched_at": meta.fetched_at.isoformat(),
                "saved_at": clock().isoformat(),
                "content_type": meta.content_type,
                "sha256": meta.sha256,
            },
            indent=2,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",  # the same bytes on Windows as in CI
    )
    return target / f"{name}.gz"
