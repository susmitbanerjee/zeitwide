"""Builds the conformance suite's golden dataset (``conformance/dataset.json``).

The golden dataset is in the release format, but it isn't a release: it is built from the
real source snapshots in ``tests/fixtures/`` instead of a database, so the Java and Python
conformance runners can check every case against real dates without running the
pipeline. Snapshots are recorded in the order they were fetched, as the pipeline would
have: the first snapshot to list a closure publishes it, and a later snapshot that
changes it publishes a new version. Reasons and basis follow the seed's rules.
"""

from collections import defaultdict
from collections.abc import Collection, Iterable, Sequence
from dataclasses import dataclass, field
from datetime import UTC, date, datetime
from itertools import groupby
from pathlib import Path

from pydantic import BaseModel

from support.goldens import Golden, goldens
from zeitwide_pipeline.calendars import CalendarDefinition, load_catalog
from zeitwide_pipeline.models import Observation
from zeitwide_pipeline.publish import basis_for
from zeitwide_pipeline.release import LICENSE, SCHEMA_VERSION, render
from zeitwide_pipeline.sources import builtin_registry

PATH = Path(__file__).parents[3] / "conformance" / "dataset.json"
# N = 0 is never cut by the pipeline (dataset/README.md).
RELEASE = "2026.10.29.0"
AS_OF = datetime(2026, 10, 29, tzinfo=UTC)

# Which snapshots to record, oldest first within each source. The 2022 GOV.UK captures
# are from just before and just after the State Funeral bank holiday was announced.
SNAPSHOTS = {
    "govuk-bank-holidays": ("archive-2022-08", "archive-2022-09", "live-2026-10-28"),
    "frbservices-holiday-schedules": ("live-2026-10-28",),
    "federalreserve-k8": ("live-2026-10-28",),
    "opm-federal-holidays": ("live-2026-10-28",),
    "ecb-public-holidays": ("live-2026-10-28",),
    "bundesbank-holidays": ("archive-2025-11", "live-2026-10-28"),
}
# Whole years only, so coverage (dataset/README.md) matches what the entries list.
YEARS = {
    "GB-EAW": range(2022, 2029),
    "GB-CHAPS": range(2022, 2029),
    "US-FEDWIRE": range(2026, 2029),
    "US-FEDACH": range(2026, 2029),
    "US-FEDERAL": range(2026, 2029),
    "EU-T2": range(2026, 2029),
    "DE": range(2026, 2028),
}


class GoldenDatasetError(ValueError):
    """The snapshots imply something the builder doesn't model, such as a removal."""


@dataclass(frozen=True, slots=True)
class Version:
    entry_date: date
    reason: str
    basis: str
    revision: int
    recorded_from: datetime
    sources: tuple[Golden, ...]
    recorded_to: datetime | None = None


@dataclass
class CalendarHistory:
    revision: int = 0
    current: dict[date, Version] = field(default_factory=dict)
    replaced: list[Version] = field(default_factory=list)


def build() -> bytes:
    calendars = load_catalog()
    histories = {calendar_id: CalendarHistory() for calendar_id in calendars}
    for recorded_at, batch in _batches():
        for calendar_id, years in YEARS.items():
            _record(calendars[calendar_id], histories[calendar_id], years, recorded_at, batch)
    document = {
        "schema_version": SCHEMA_VERSION,
        "release": RELEASE,
        "as_of": AS_OF.isoformat(),
        "license": LICENSE,
        "calendars": [
            _calendar(calendars[calendar_id], histories[calendar_id])
            for calendar_id in sorted(calendars)
        ],
    }
    return render(document)


def _batches() -> list[tuple[datetime, list[Golden]]]:
    """Snapshots grouped by the day they were fetched, recorded at that day's last fetch."""
    chosen = [
        golden for golden in goldens(set(SNAPSHOTS)) if golden.name in SNAPSHOTS[golden.source_id]
    ]
    chosen.sort(key=lambda golden: golden.meta.fetched_at)
    batches = []
    for _, group in groupby(chosen, key=lambda golden: golden.meta.fetched_at.date()):
        snapshots = list(group)
        batches.append((max(golden.meta.fetched_at for golden in snapshots), snapshots))
    return batches


def _record(
    calendar: CalendarDefinition,
    history: CalendarHistory,
    years: range,
    recorded_at: datetime,
    batch: Sequence[Golden],
) -> None:
    evidence = _evidence(calendar.id, batch)
    check_nothing_removed(calendar.id, history.current, evidence, years)
    changed = False
    for entry_date in sorted(evidence):
        rows = evidence[entry_date]
        if entry_date.year not in years or all(o.is_open for _, o in rows):
            continue
        if any(o.is_open for _, o in rows):
            raise GoldenDatasetError(f"Sources disagree on {calendar.id} {entry_date}")
        operator_rows = [o for g, o in rows if g.source_id in calendar.operator_sources]
        reason = (operator_rows or [o for _, o in rows])[0].reason
        basis = basis_for((g.source_id for g, _ in rows), calendar.operator_sources).value
        before = history.current.get(entry_date)
        if before is not None and (before.reason, before.basis) == (reason, basis):
            continue
        if not changed:
            history.revision += 1
            changed = True
        if before is not None:
            history.replaced.append(_closed(before, recorded_at))
        history.current[entry_date] = Version(
            entry_date,
            reason,
            basis,
            history.revision,
            recorded_at,
            tuple(sorted({g for g, _ in rows}, key=lambda g: (g.source_id, g.name))),
        )


def _evidence(
    calendar_id: str, batch: Iterable[Golden]
) -> dict[date, list[tuple[Golden, Observation]]]:
    evidence: dict[date, list[tuple[Golden, Observation]]] = defaultdict(list)
    for golden in batch:
        for observation in golden.expected():
            if observation.calendar_id == calendar_id:
                evidence[observation.entry_date].append((golden, observation))
    return evidence


def check_nothing_removed(
    calendar_id: str, current: Iterable[date], listed: Collection[date], years: range
) -> None:
    """A source that still lists a year but drops a closure would be a removal."""
    listed_years = {entry_date.year for entry_date in listed}
    for entry_date in current:
        still_listed = entry_date.year in listed_years and entry_date.year in years
        if still_listed and entry_date not in listed:
            raise GoldenDatasetError(f"{calendar_id} {entry_date} disappeared")


def _closed(version: Version, recorded_at: datetime) -> Version:
    return Version(
        version.entry_date,
        version.reason,
        version.basis,
        version.revision,
        version.recorded_from,
        version.sources,
        recorded_at,
    )


def _calendar(calendar: CalendarDefinition, history: CalendarHistory) -> dict[str, object]:
    versions = sorted(
        [*history.replaced, *history.current.values()],
        key=lambda version: (version.entry_date, version.recorded_from),
    )
    return {
        "id": calendar.id,
        "name": calendar.name,
        "kind": calendar.kind.value,
        "timezone": calendar.timezone,
        "weekend": [day.value for day in calendar.weekend],
        "closes_on_holidays": calendar.closes_on_holidays,
        "observance": _model(calendar.observance),
        "operating_hours": _model(calendar.operating_hours),
        "revision": history.revision,
        "entries": [_entry(version) for version in versions],
    }


def _entry(version: Version) -> dict[str, object]:
    return {
        "date": version.entry_date.isoformat(),
        "is_open": False,
        "reason": version.reason,
        "basis": version.basis,
        "revision": version.revision,
        "recorded_from": _utc(version.recorded_from),
        "recorded_to": None if version.recorded_to is None else _utc(version.recorded_to),
        "sources": [
            {
                "source_id": golden.source_id,
                "publisher": builtin_registry().get(golden.source_id).publisher,
                "url": golden.meta.url,
                "sha256": golden.meta.sha256,
                "fetched_at": _utc(golden.meta.fetched_at),
            }
            for golden in version.sources
        ],
    }


def _model(model: BaseModel | None) -> dict[str, object] | None:
    """As the pipeline stores it in the ``calendar`` table."""
    return None if model is None else model.model_dump(mode="json", exclude_none=True)


def _utc(moment: datetime) -> str:
    return moment.astimezone(UTC).isoformat()
