from datetime import date, timedelta

from support.doubles import START, RecordingTelemetry
from support.fixture_source import HolidayTableParser, HolidayTableSource
from zeitwide_pipeline.db import ReviewQueue, SourceHealth
from zeitwide_pipeline.health import report_health

SOURCE = HolidayTableSource(
    id="fixture-holidays",
    url="https://fixtures.example.test/holidays",
    parser=HolidayTableParser("US-FEDWIRE"),
)
NEVER_REGISTERED = HolidayTableSource(
    id="not-in-database",
    url="https://fixtures.example.test/other",
    parser=HolidayTableParser("US-FEDWIRE"),
)
EMPTY_QUEUE = ReviewQueue(0, None, None)


def test_should_report_stored_freshness_and_failures_per_source() -> None:
    recorder = RecordingTelemetry()
    last_success = START - timedelta(hours=5)

    report_health(
        recorder.telemetry,
        [SOURCE],
        {SOURCE.id: SourceHealth(last_success, consecutive_failures=3, suspended=True)},
        EMPTY_QUEUE,
        START,
    )

    assert recorder.metric("zeitwide.source.last_success_timestamp", SOURCE.id) == (
        last_success.timestamp()
    )
    assert recorder.metric("zeitwide.source.schedule", SOURCE.id) == 86400
    assert recorder.metric("zeitwide.source.consecutive_failures", SOURCE.id) == 3
    assert recorder.metric("zeitwide.source.suspended", SOURCE.id) == 1


def test_should_skip_freshness_for_a_source_never_checked_successfully() -> None:
    recorder = RecordingTelemetry()

    report_health(
        recorder.telemetry,
        [SOURCE, NEVER_REGISTERED],
        {SOURCE.id: SourceHealth(None, consecutive_failures=1, suspended=False)},
        EMPTY_QUEUE,
        START,
    )

    assert recorder.metric("zeitwide.source.last_success_timestamp", SOURCE.id) is None
    assert recorder.metric("zeitwide.source.consecutive_failures", SOURCE.id) == 1
    assert recorder.metric("zeitwide.source.schedule", NEVER_REGISTERED.id) is None


def test_should_report_queue_size_age_and_notice() -> None:
    recorder = RecordingTelemetry()
    queue = ReviewQueue(
        pending=4,
        oldest_created_at=START - timedelta(hours=30),
        soonest_entry_date=START.date() + timedelta(days=10),
    )

    report_health(recorder.telemetry, [], {}, queue, START)

    assert recorder.metric("zeitwide.review.candidates_pending") == 4
    assert recorder.metric("zeitwide.review.oldest_candidate_age") == 30 * 3600
    assert recorder.metric("zeitwide.review.min_notice") == 10


def test_should_report_only_the_count_when_nothing_is_pending() -> None:
    recorder = RecordingTelemetry()

    report_health(recorder.telemetry, [], {}, EMPTY_QUEUE, START)

    assert recorder.metric("zeitwide.review.candidates_pending") == 0
    assert recorder.metric("zeitwide.review.oldest_candidate_age") is None
    assert recorder.metric("zeitwide.review.min_notice") is None


def test_should_report_negative_notice_for_a_date_already_past() -> None:
    recorder = RecordingTelemetry()
    queue = ReviewQueue(1, START, date(2026, 10, 26))

    report_health(recorder.telemetry, [], {}, queue, START)

    assert recorder.metric("zeitwide.review.min_notice") == -2
