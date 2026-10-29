"""fetch -> parse -> diff -> corroborate through Procrastinate, on real Postgres and fake-gcs.

The schema comes from api/'s Flyway migrations, applied in order, and the pipeline runs
as the least-privilege ``settlecal_pipeline`` login, as on Cloud Run. HTTP is mocked with
respx; nothing reaches a real source.
"""

from collections.abc import Iterator
from dataclasses import dataclass
from datetime import timedelta
from functools import partial

import httpx
import procrastinate
import psycopg
import pytest
import respx
import structlog
from google.cloud import storage
from tenacity import wait_none
from typer.testing import CliRunner

from settlecal_pipeline.check import MAX_CONSECUTIVE_FAILURES, SourceChecker
from settlecal_pipeline.cli import EXIT_NOT_FOUND, app
from settlecal_pipeline.db import open_store
from settlecal_pipeline.fetch import Fetcher, HostThrottle, new_client
from settlecal_pipeline.jobs import PipelineContext, create_app, run_due_blocking
from settlecal_pipeline.models import Observation, SourceRole
from settlecal_pipeline.sources import SourceRegistry
from settlecal_pipeline.storage import GcsSnapshotStore
from support.database import Database
from support.doubles import FakeClock, RecordingTelemetry
from support.fixture_source import HolidayTableParser, HolidayTableSource, holiday_page

PRIMARY_URL = "https://fed.example.test/holidays"
SECOND_URL = "https://board.example.test/k8"
PRIMARY = HolidayTableSource(
    id="fixture-fed-schedule", url=PRIMARY_URL, parser=HolidayTableParser("US-FEDWIRE")
)
SECOND = HolidayTableSource(
    id="fixture-board-k8",
    url=SECOND_URL,
    parser=HolidayTableParser("US-FEDWIRE"),
    publisher="Fixture Board",
    role=SourceRole.CORROBORATING,
)
PRIMARY_PAGE = holiday_page(
    ("2026-06-19", "Juneteenth", "Closed"),
    ("2026-11-26", "Thanksgiving Day", "Closed"),
    ("2026-12-25", "Christmas Day", "Closed"),
)
# It lists every 2026 closure it knows, or diffing would propose reopening the rest.
SECOND_PAGE = holiday_page(
    ("2026-11-26", "Thanksgiving", "Closed"), ("2026-12-25", "Christmas", "Closed")
)


@pytest.fixture
def database(migrated: Database) -> Database:
    """The migrated database with one published Fedwire closure to diff against."""
    with psycopg.connect(migrated.admin, autocommit=True) as connection:
        connection.execute(
            """
            INSERT INTO calendar (id, name, kind, timezone, weekend_rule)
            VALUES ('US-FEDWIRE', 'Fedwire Funds Service', 'rail', 'America/New_York', '{}');
            SELECT publish_entry_version('US-FEDWIRE', DATE '2026-12-25', false, 'Christmas Day',
                                         'published', 1, TIMESTAMPTZ '2026-01-01Z');
            """
        )
    return migrated


@dataclass
class Pipeline:
    clock: FakeClock
    recorder: RecordingTelemetry
    app: procrastinate.App
    context: PipelineContext

    def run_due(self) -> list[str]:
        return run_due_blocking(self.app, self.context, install_signal_handlers=False)


@pytest.fixture
def pipeline(database: Database, gcs_endpoint: str, bucket: storage.Bucket) -> Iterator[Pipeline]:
    clock = FakeClock()
    recorder = RecordingTelemetry()
    with new_client() as client:
        fetcher = Fetcher(
            client,
            "test",
            clock,
            throttle=HostThrottle(sleep=lambda _: None),
            retry_wait=wait_none(),
        )
        context = PipelineContext(
            registry=SourceRegistry([PRIMARY, SECOND]),
            calendars={},
            open_store=partial(open_store, database.pipeline),
            checker=SourceChecker(
                fetcher,
                GcsSnapshotStore.connect(bucket.name, gcs_endpoint),
                recorder.telemetry,
                clock,
            ),
            clock=clock,
        )
        connector = procrastinate.PsycopgConnector(
            conninfo=database.pipeline, min_size=1, max_size=1
        )
        yield Pipeline(clock, recorder, create_app(connector, context), context)


@pytest.fixture
def router() -> Iterator[respx.MockRouter]:
    with respx.mock(assert_all_called=False) as mock:
        mock.get("https://fed.example.test/robots.txt").respond(404)
        mock.get("https://board.example.test/robots.txt").respond(404)
        yield mock


def serve(router: respx.MockRouter, url: str, page: bytes, etag: str) -> respx.Route:
    """Answers 304 when the client sends back the ETag it was given."""

    def respond(request: httpx.Request) -> httpx.Response:
        if request.headers.get("If-None-Match") == etag:
            return httpx.Response(304)
        return httpx.Response(
            200, content=page, headers={"Content-Type": "text/html", "ETag": etag}
        )

    return router.get(url).mock(side_effect=respond)


def rows(conninfo: str, sql: str, params: tuple[object, ...] = ()) -> list[tuple[object, ...]]:
    with psycopg.connect(conninfo) as connection:
        return connection.execute(sql, params).fetchall()


def test_new_pages_become_snapshots_observations_and_candidates(
    router: respx.MockRouter, pipeline: Pipeline, database: Database, bucket: storage.Bucket
) -> None:
    serve(router, PRIMARY_URL, PRIMARY_PAGE, '"p1"')
    serve(router, SECOND_URL, SECOND_PAGE, '"s1"')

    assert sorted(pipeline.run_due()) == [SECOND.id, PRIMARY.id]

    snapshots = rows(database.admin, "SELECT object_key FROM source_snapshot ORDER BY id")
    assert len(snapshots) == 2
    assert all(bucket.blob(str(key)).exists() for (key,) in snapshots)
    assert rows(database.admin, "SELECT count(*) FROM observation") == [(5,)]
    # Christmas matches the published entry, so only two dates change.
    assert rows(
        database.admin,
        """
        SELECT entry_date::text, corroboration, source_count, after_state ->> 'is_open'
        FROM candidate_change ORDER BY entry_date
        """,
    ) == [
        ("2026-06-19", "single_source", 1, "false"),
        ("2026-11-26", "corroborated", 2, "false"),
    ]


def test_jobs_run_on_procrastinate_and_finish(
    router: respx.MockRouter, pipeline: Pipeline, database: Database
) -> None:
    serve(router, PRIMARY_URL, PRIMARY_PAGE, '"p1"')
    serve(router, SECOND_URL, SECOND_PAGE, '"s1"')

    pipeline.run_due()

    assert rows(
        database.admin, "SELECT task_name, status::text FROM procrastinate_jobs ORDER BY id"
    ) == [("check_source", "succeeded"), ("check_source", "succeeded")]


def test_next_run_is_idempotent(
    router: respx.MockRouter, pipeline: Pipeline, database: Database
) -> None:
    primary = serve(router, PRIMARY_URL, PRIMARY_PAGE, '"p1"')
    serve(router, SECOND_URL, SECOND_PAGE, '"s1"')
    pipeline.run_due()
    before = _counts(database)

    pipeline.clock.advance(timedelta(days=1))
    pipeline.run_due()

    assert primary.calls.last.response.status_code == httpx.codes.NOT_MODIFIED
    assert _counts(database) == before


def test_rediffing_the_same_snapshot_changes_nothing(
    router: respx.MockRouter, pipeline: Pipeline, database: Database
) -> None:
    serve(router, PRIMARY_URL, PRIMARY_PAGE, '"p1"')
    serve(router, SECOND_URL, SECOND_PAGE, '"s1"')
    pipeline.run_due()
    before = _counts(database)
    snapshot_id = int(str(rows(database.admin, "SELECT min(id) FROM source_snapshot")[0][0]))
    observations = [
        Observation(str(calendar_id), entry_date, bool(is_open), str(reason))  # type: ignore[arg-type]
        for calendar_id, entry_date, is_open, reason in rows(
            database.admin,
            "SELECT calendar_id, entry_date, is_open, reason FROM observation"
            " WHERE snapshot_id = %s",
            (snapshot_id,),
        )
    ]

    with open_store(database.pipeline) as store, store.transaction():
        store.record_diff(snapshot_id, "1", observations)

    assert _counts(database) == before


def test_failing_source_is_suspended_and_skipped(
    router: respx.MockRouter, pipeline: Pipeline, database: Database
) -> None:
    router.get(PRIMARY_URL).respond(500)
    serve(router, SECOND_URL, SECOND_PAGE, '"s1"')

    for _ in range(MAX_CONSECUTIVE_FAILURES):
        pipeline.run_due()
        pipeline.clock.advance(timedelta(days=1))

    assert PRIMARY.id not in pipeline.run_due()
    assert rows(
        database.admin,
        """
        SELECT consecutive_failures, suspended_at IS NOT NULL FROM source_state st
        JOIN source s ON s.id = st.source_id WHERE s.slug = 'fixture-fed-schedule'
        """,
    ) == [(MAX_CONSECUTIVE_FAILURES, True)]
    assert pipeline.recorder.metric("settlecal.source.suspended", PRIMARY.id) == 1


def test_resumed_source_is_checked_again(
    router: respx.MockRouter, pipeline: Pipeline, database: Database
) -> None:
    router.get(PRIMARY_URL).respond(500)
    serve(router, SECOND_URL, SECOND_PAGE, '"s1"')
    for _ in range(MAX_CONSECUTIVE_FAILURES):
        pipeline.run_due()
        pipeline.clock.advance(timedelta(days=1))

    with open_store(database.pipeline) as store:
        assert store.resume(PRIMARY.id)

    assert PRIMARY.id in pipeline.run_due()


def test_pipeline_login_cannot_edit_entries_directly(database: Database) -> None:
    with (
        psycopg.connect(database.pipeline) as connection,
        pytest.raises(psycopg.errors.InsufficientPrivilege),
    ):
        connection.execute("UPDATE calendar_entry SET reason = 'Edited'")


def _counts(database: Database) -> list[tuple[object, ...]]:
    return rows(
        database.admin,
        """
        SELECT (SELECT count(*) FROM source_snapshot), (SELECT count(*) FROM observation),
               (SELECT count(*) FROM candidate_change), (SELECT count(*) FROM candidate_observation)
        """,
    )


@pytest.fixture
def cli_env(
    monkeypatch: pytest.MonkeyPatch, database: Database, gcs_endpoint: str, bucket: storage.Bucket
) -> Iterator[None]:
    monkeypatch.setenv("SETTLECAL_DATABASE_CONNINFO", database.pipeline)
    monkeypatch.setenv("SETTLECAL_SNAPSHOT_BUCKET", bucket.name)
    monkeypatch.setenv("SETTLECAL_GCS_ENDPOINT", gcs_endpoint)
    monkeypatch.setenv("SETTLECAL_BOT_CONTACT_URL", "https://settlecal.example/bot")
    monkeypatch.delenv("OTEL_EXPORTER_OTLP_ENDPOINT", raising=False)
    yield
    structlog.reset_defaults()


@pytest.mark.usefixtures("cli_env")
def test_cli_run_due_wires_the_shipped_catalog() -> None:
    assert CliRunner().invoke(app, ["run-due"]).exit_code == 0


@pytest.mark.usefixtures("cli_env")
def test_cli_resume_reports_a_source_that_is_not_suspended() -> None:
    assert CliRunner().invoke(app, ["resume-source", "no-such-source"]).exit_code == EXIT_NOT_FOUND
