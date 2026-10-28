"""One check of one source: monitor -> parse -> diff -> corroborate."""

import hashlib
from collections.abc import Mapping
from contextlib import AbstractContextManager
from dataclasses import dataclass
from enum import StrEnum

import structlog
from opentelemetry.trace import Span, Status, StatusCode

from settlecal_pipeline.calendars import CalendarDefinition, operator_calendars
from settlecal_pipeline.clock import Clock
from settlecal_pipeline.db import PipelineStore, SourceState
from settlecal_pipeline.fetch import Fetched, Fetcher, FetchError, Unchanged, Validators
from settlecal_pipeline.models import Corroboration, SnapshotMeta
from settlecal_pipeline.retrieve import fetch_source
from settlecal_pipeline.sources import ParseError, Source, parse_snapshot
from settlecal_pipeline.storage import SnapshotStore, snapshot_key
from settlecal_pipeline.telemetry import Telemetry, source_attributes

# After this many failed checks in a row a source is suspended until someone resumes it,
# so a broken scraper alerts instead of quietly looking like "no change".
MAX_CONSECUTIVE_FAILURES = 3

log = structlog.get_logger(__name__)


class CheckResult(StrEnum):
    UNCHANGED = "unchanged"
    PROCESSED = "processed"
    FAILED = "failed"
    SUSPENDED = "suspended"
    SKIPPED = "skipped"


@dataclass(frozen=True, slots=True)
class _Snapshot:
    fetched: Fetched
    sha256: str
    db_id: int


class SourceChecker:
    def __init__(
        self,
        fetcher: Fetcher,
        snapshots: SnapshotStore,
        telemetry: Telemetry,
        clock: Clock,
        calendars: Mapping[str, CalendarDefinition] | None = None,
    ) -> None:
        self._fetcher = fetcher
        self._snapshots = snapshots
        self._telemetry = telemetry
        self._clock = clock
        self._calendars = calendars or {}

    def check(self, source: Source, source_db_id: int, store: PipelineStore) -> CheckResult:
        attributes = source_attributes(source.id)
        with (
            structlog.contextvars.bound_contextvars(source_id=source.id),
            self._telemetry.tracer.start_as_current_span(
                "source.check", attributes=attributes
            ) as span,
        ):
            state = store.load_state(source_db_id)
            if state.suspended_at is not None:
                log.warning("source_suspended_skipped", suspended_at=state.suspended_at.isoformat())
                self._telemetry.suspended.set(1, attributes)
                return CheckResult.SKIPPED
            try:
                result = self._run(source, source_db_id, state, store)
            except (FetchError, ParseError) as error:
                return self._fail(source, source_db_id, state, store, error=error, span=span)
            except Exception as error:
                self._fail(source, source_db_id, state, store, error=error, span=span)
                raise
            self._telemetry.last_success.set(self._clock().timestamp(), attributes)
            self._telemetry.suspended.set(0, attributes)
            return result

    def _run(
        self, source: Source, source_db_id: int, state: SourceState, store: PipelineStore
    ) -> CheckResult:
        checked_at = self._clock()
        snapshot = self._monitor(source, source_db_id, state, store)
        if snapshot is None:
            log.info("source_unchanged")
            return CheckResult.UNCHANGED
        with structlog.contextvars.bound_contextvars(snapshot_sha256=snapshot.sha256):
            meta = SnapshotMeta(
                source_id=source.id,
                url=snapshot.fetched.url or source.url,
                fetched_at=snapshot.fetched.fetched_at,
                content_type=snapshot.fetched.content_type,
                sha256=snapshot.sha256,
            )
            with self._step("source.parse", source, snapshot.sha256):
                try:
                    observations = parse_snapshot(source.parser, snapshot.fetched.content, meta)
                except ParseError:
                    self._telemetry.parse_failures.add(1, source_attributes(source.id))
                    raise
            with store.transaction():
                with self._step("source.diff", source, snapshot.sha256):
                    outcome = store.record_diff(
                        snapshot.db_id,
                        source.parser.version,
                        observations,
                        operator_calendars(source.id, self._calendars),
                    )
                with self._step("source.corroborate", source, snapshot.sha256):
                    statuses = store.corroborate(outcome.candidate_ids)
                store.record_success(
                    source_db_id, checked_at, snapshot.fetched.validators, snapshot.sha256
                )
            log.info(
                "source_processed",
                observations=len(observations),
                new_candidates=outcome.new_candidates,
                conflicting=outcome.conflicting,
                corroborated=sum(
                    1 for status in statuses.values() if status is Corroboration.CORROBORATED
                ),
            )
        return CheckResult.PROCESSED

    def _monitor(
        self, source: Source, source_db_id: int, state: SourceState, store: PipelineStore
    ) -> _Snapshot | None:
        """Fetches the source; stores new bytes and their snapshot row. None if unchanged."""
        checked_at = self._clock()
        with self._step("source.monitor", source):
            # Validators are trusted only once their response was fully processed.
            validators = state.validators if state.last_parsed_sha256 else Validators()
            result = fetch_source(self._fetcher, source, validators)
            if isinstance(result, Unchanged):
                store.record_success(source_db_id, checked_at, validators, state.last_parsed_sha256)
                return None
            sha256 = hashlib.sha256(result.content).hexdigest()
            if sha256 == state.last_parsed_sha256:
                store.record_success(source_db_id, checked_at, result.validators, sha256)
                return None
            key = snapshot_key(sha256)
            # Bytes first, then the row, so a snapshot row never points at a missing object.
            self._snapshots.put(key, result.content, result.content_type)
            snapshot_id = store.save_snapshot(source_db_id, sha256, key, result)
        return _Snapshot(result, sha256, snapshot_id)

    def _step(
        self, name: str, source: Source, sha256: str | None = None
    ) -> AbstractContextManager[Span]:
        """A child span; an escaping exception is recorded on it and marks it failed."""
        attributes = source_attributes(source.id)
        if sha256 is not None:
            attributes["settlecal.snapshot_sha256"] = sha256
        return self._telemetry.tracer.start_as_current_span(name, attributes=attributes)

    def _fail(  # noqa: PLR0913 - the failure needs the whole check's context
        self,
        source: Source,
        source_db_id: int,
        state: SourceState,
        store: PipelineStore,
        *,
        error: Exception,
        span: Span,
    ) -> CheckResult:
        span.record_exception(error)
        span.set_status(Status(StatusCode.ERROR, type(error).__name__))
        failures = state.consecutive_failures + 1
        suspend = failures >= MAX_CONSECUTIVE_FAILURES
        store.record_failure(
            source_db_id,
            self._clock(),
            failures,
            f"{type(error).__name__}: {error}",
            suspend=suspend,
        )
        if suspend:
            self._telemetry.suspended.set(1, source_attributes(source.id))
            log.error("source_suspended", consecutive_failures=failures, error=str(error))
            return CheckResult.SUSPENDED
        log.warning("source_check_failed", consecutive_failures=failures, error=str(error))
        return CheckResult.FAILED
