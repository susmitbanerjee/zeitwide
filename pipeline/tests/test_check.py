"""SourceChecker with mocked HTTP and an in-memory store."""

import hashlib
from collections.abc import Iterable, Iterator, Sequence
from contextlib import nullcontext
from dataclasses import dataclass, field
from datetime import date, datetime

import pytest
import respx
from tenacity import wait_none

from support.doubles import FakeClock, MemorySnapshotStore, RecordingTelemetry
from support.fixture_source import HolidayTableParser, HolidayTableSource, holiday_page
from zeitwide_pipeline.calendars import CalendarDefinition
from zeitwide_pipeline.check import MAX_CONSECUTIVE_FAILURES, CheckResult, SourceChecker
from zeitwide_pipeline.db import DiffOutcome, RegisteredSource, SourceState
from zeitwide_pipeline.fetch import Fetched, Fetcher, HostThrottle, Validators, new_client
from zeitwide_pipeline.models import Corroboration, Observation
from zeitwide_pipeline.sources import Source
from zeitwide_pipeline.storage import snapshot_key

URL = "https://fixtures.example.test/holidays"
SOURCE = HolidayTableSource(id="fixture-holidays", url=URL, parser=HolidayTableParser("US-FEDWIRE"))
PAGE = holiday_page(("2026-11-26", "Thanksgiving Day", "Closed"))
PAGE_SHA = hashlib.sha256(PAGE).hexdigest()
SOURCE_DB_ID = 1


@dataclass
class FakeStore:
    """Keeps one source's state in memory and records what the checker asked for."""

    state: SourceState = field(
        default_factory=lambda: SourceState(Validators(), None, None, 0, None)
    )
    snapshots: list[str] = field(default_factory=list)
    diffs: list[tuple[int, str, list[Observation]]] = field(default_factory=list)
    successes: int = 0

    def transaction(self) -> nullcontext[None]:
        return nullcontext()

    def register_calendars(self, calendars: Iterable[CalendarDefinition]) -> None:
        return

    def register_sources(self, sources: Iterable[Source]) -> dict[str, RegisteredSource]:
        return {source.id: RegisteredSource(SOURCE_DB_ID, enabled=True) for source in sources}

    def find_source(self, slug: str) -> RegisteredSource | None:
        return RegisteredSource(SOURCE_DB_ID, enabled=True)

    def load_states(self) -> dict[str, SourceState]:
        return {}

    def load_state(self, source_db_id: int) -> SourceState:
        return self.state

    def save_snapshot(
        self, source_db_id: int, sha256: str, object_key: str, fetched: Fetched
    ) -> int:
        self.snapshots.append(sha256)
        return len(self.snapshots)

    def record_observations(
        self, snapshot_id: int, parser_version: str, observations: Sequence[Observation]
    ) -> dict[tuple[str, date], int]:
        return {}

    def record_diff(
        self,
        snapshot_id: int,
        parser_version: str,
        observations: Sequence[Observation],
        operator_calendars: frozenset[str] = frozenset(),
    ) -> DiffOutcome:
        self.diffs.append((snapshot_id, parser_version, list(observations)))
        return DiffOutcome(candidate_ids=(10,), new_candidates=1, conflicting=0)

    def corroborate(self, candidate_ids: Sequence[int]) -> dict[int, Corroboration]:
        return dict.fromkeys(candidate_ids, Corroboration.SINGLE_SOURCE)

    def record_success(
        self,
        source_db_id: int,
        checked_at: datetime,
        validators: Validators,
        parsed_sha256: str | None,
    ) -> None:
        self.successes += 1
        self.state = SourceState(validators, parsed_sha256, checked_at, 0, None)

    def record_failure(
        self,
        source_db_id: int,
        checked_at: datetime,
        consecutive_failures: int,
        error: str,
        *,
        suspend: bool,
    ) -> None:
        self.state = SourceState(
            self.state.validators,
            self.state.last_parsed_sha256,
            checked_at,
            consecutive_failures,
            checked_at if suspend else None,
        )

    def resume(self, slug: str) -> bool:
        return True


class BrokenDiffStore(FakeStore):
    def record_diff(
        self,
        snapshot_id: int,
        parser_version: str,
        observations: Sequence[Observation],
        operator_calendars: frozenset[str] = frozenset(),
    ) -> DiffOutcome:
        raise RuntimeError("bug")


@pytest.fixture
def router() -> Iterator[respx.MockRouter]:
    with respx.mock(assert_all_called=False) as mock:
        mock.get("https://fixtures.example.test/robots.txt").respond(404)
        yield mock


@pytest.fixture
def recorder() -> RecordingTelemetry:
    return RecordingTelemetry()


@pytest.fixture
def snapshots() -> MemorySnapshotStore:
    return MemorySnapshotStore()


@pytest.fixture
def checker(
    recorder: RecordingTelemetry, snapshots: MemorySnapshotStore
) -> Iterator[SourceChecker]:
    clock = FakeClock()
    with new_client() as client:
        fetcher = Fetcher(
            client,
            "test",
            clock,
            throttle=HostThrottle(sleep=lambda _: None),
            retry_wait=wait_none(),
        )
        yield SourceChecker(fetcher, snapshots, recorder.telemetry, clock)


def serve(router: respx.MockRouter, content: bytes = PAGE, status: int = 200) -> respx.Route:
    return router.get(URL).respond(
        status, content=content, headers={"Content-Type": "text/html", "ETag": '"v1"'}
    )


def test_new_page_is_stored_parsed_and_diffed(
    router: respx.MockRouter, checker: SourceChecker, snapshots: MemorySnapshotStore
) -> None:
    serve(router)
    store = FakeStore()

    result = checker.check(SOURCE, SOURCE_DB_ID, store)

    assert result is CheckResult.PROCESSED
    assert snapshots.objects[snapshot_key(PAGE_SHA)] == (PAGE, "text/html")
    assert store.snapshots == [PAGE_SHA]
    assert store.diffs == [
        (1, "1", [Observation("US-FEDWIRE", date(2026, 11, 26), False, "Thanksgiving Day")])
    ]
    assert store.state.last_parsed_sha256 == PAGE_SHA
    assert store.state.validators == Validators('"v1"', None)


def test_each_step_gets_a_span(
    router: respx.MockRouter, checker: SourceChecker, recorder: RecordingTelemetry
) -> None:
    serve(router)

    checker.check(SOURCE, SOURCE_DB_ID, FakeStore())

    assert recorder.span_names() == [
        "source.monitor",
        "source.parse",
        "source.diff",
        "source.corroborate",
        "source.check",
    ]


def test_success_records_last_success_timestamp(
    router: respx.MockRouter, checker: SourceChecker, recorder: RecordingTelemetry
) -> None:
    serve(router)

    checker.check(SOURCE, SOURCE_DB_ID, FakeStore())

    assert recorder.metric("zeitwide.source.last_success_timestamp", SOURCE.id) == pytest.approx(
        FakeClock().now.timestamp()
    )


def test_same_bytes_are_not_stored_or_parsed_again(
    router: respx.MockRouter, checker: SourceChecker, snapshots: MemorySnapshotStore
) -> None:
    serve(router)
    store = FakeStore(state=SourceState(Validators(), PAGE_SHA, None, 0, None))

    result = checker.check(SOURCE, SOURCE_DB_ID, store)

    assert result is CheckResult.UNCHANGED
    assert (store.snapshots, store.diffs, snapshots.objects) == ([], [], {})


def test_not_modified_counts_as_success(router: respx.MockRouter, checker: SourceChecker) -> None:
    serve(router, content=b"", status=304)
    store = FakeStore(state=SourceState(Validators('"v1"'), PAGE_SHA, None, 2, None))

    result = checker.check(SOURCE, SOURCE_DB_ID, store)

    assert result is CheckResult.UNCHANGED
    assert store.state.consecutive_failures == 0


def test_validators_are_not_sent_until_a_snapshot_parsed(
    router: respx.MockRouter, checker: SourceChecker
) -> None:
    route = serve(router)
    store = FakeStore(state=SourceState(Validators('"v1"'), None, None, 1, None))

    checker.check(SOURCE, SOURCE_DB_ID, store)

    assert "If-None-Match" not in route.calls.last.request.headers


def test_parse_failure_is_counted_and_recorded(
    router: respx.MockRouter, checker: SourceChecker, recorder: RecordingTelemetry
) -> None:
    serve(router, content=b"<html><p>Page moved</p></html>")
    store = FakeStore()

    result = checker.check(SOURCE, SOURCE_DB_ID, store)

    assert result is CheckResult.FAILED
    assert store.state.consecutive_failures == 1
    assert store.state.last_parsed_sha256 is None
    assert store.snapshots, "the snapshot that failed to parse is kept as evidence"
    assert recorder.metric("zeitwide.source.parse_failures", SOURCE.id) == 1


def test_failed_step_span_is_marked_as_error(
    router: respx.MockRouter, checker: SourceChecker, recorder: RecordingTelemetry
) -> None:
    serve(router, content=b"<html></html>")

    checker.check(SOURCE, SOURCE_DB_ID, FakeStore())

    failed = {span.name for span in recorder.finished_spans() if not span.status.is_ok}
    assert failed == {"source.parse", "source.check"}


def test_source_is_suspended_after_consecutive_failures(
    router: respx.MockRouter, checker: SourceChecker, recorder: RecordingTelemetry
) -> None:
    serve(router, status=404)
    store = FakeStore()

    results = [checker.check(SOURCE, SOURCE_DB_ID, store) for _ in range(MAX_CONSECUTIVE_FAILURES)]

    assert results == [CheckResult.FAILED, CheckResult.FAILED, CheckResult.SUSPENDED]
    assert store.state.suspended_at is not None
    assert recorder.metric("zeitwide.source.suspended", SOURCE.id) == 1


def test_suspended_source_is_not_fetched(router: respx.MockRouter, checker: SourceChecker) -> None:
    route = serve(router)
    store = FakeStore(state=SourceState(Validators(), None, None, 3, FakeClock().now))

    result = checker.check(SOURCE, SOURCE_DB_ID, store)

    assert result is CheckResult.SKIPPED
    assert not route.called


def test_unexpected_error_is_recorded_then_raised(
    router: respx.MockRouter, checker: SourceChecker
) -> None:
    serve(router)
    store = BrokenDiffStore()

    with pytest.raises(RuntimeError, match="bug"):
        checker.check(SOURCE, SOURCE_DB_ID, store)
    assert store.state.consecutive_failures == 1
