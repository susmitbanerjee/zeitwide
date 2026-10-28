"""Fixture change -> candidate -> approve -> new entry version -> change event + outbox -> release.

Everything runs against real Postgres (migrated by api/'s Flyway files) and fake-gcs, as
the least-privilege pipeline login. The sources are the real snapshots in
``tests/fixtures/``, served over mocked HTTP. The release must match
``dataset/schema/release.schema.json`` and pass ``conformance/cases/`` through the open
library.
"""

import hashlib
import json
from collections.abc import Iterator, Mapping, Sequence
from contextlib import contextmanager
from dataclasses import dataclass
from datetime import date, timedelta
from functools import partial
from pathlib import Path

import httpx
import jsonschema
import procrastinate
import psycopg
import pytest
import respx
from google.cloud import storage
from psycopg.rows import TupleRow, tuple_row
from settlecal.conformance import load_cases, run_cases
from settlecal.release import parse_release
from tenacity import wait_none

from settlecal_pipeline.backup import DatabaseBackup
from settlecal_pipeline.calendars import CalendarDefinition, load_catalog
from settlecal_pipeline.check import SourceChecker
from settlecal_pipeline.daily import DailyJob, DailyResult
from settlecal_pipeline.db import open_store, run_cleanup
from settlecal_pipeline.fetch import Fetcher, HostThrottle, new_client
from settlecal_pipeline.github import DatasetRepository
from settlecal_pipeline.jobs import PipelineContext, create_app, run_due_blocking
from settlecal_pipeline.release import PostgresReleaseStore, release_object_key
from settlecal_pipeline.review_db import PublishResult, Review, open_review_store
from settlecal_pipeline.seed import PostgresSeedStore, Seeder
from settlecal_pipeline.sources import builtin_registry
from settlecal_pipeline.sources.catalog import federalreserve_k8, frbservices_holiday_schedules
from settlecal_pipeline.storage import GcsSnapshotStore
from support.database import Database
from support.doubles import FakeClock, RecordingTelemetry
from support.goldens import goldens

REPO_ROOT = Path(__file__).parents[2]
RELEASE_SCHEMA = REPO_ROOT / "dataset" / "schema" / "release.schema.json"
CASES = REPO_ROOT / "conformance" / "cases"
DATASET_REPOSITORY = "settlecal/dataset"
GITHUB_HOST = "api.github.com"
GITHUB_PATH = f"/repos/{DATASET_REPOSITORY}"
MOVED_COLUMBUS_DAY = date(2027, 10, 12)
SEED_YEARS = frozenset({2026, 2027})
REVIEW = Review(reviewer="cli:test-reviewer", reason="Both Federal Reserve sources agree")

REGISTRY = builtin_registry()
BUNDESBANK_PDF_PATH = "/resource/blob/764518/0000/x/feiertage-in-deutschland-1-data.pdf"
BUNDESBANK_2026 = next(
    g.content for g in goldens({"bundesbank-holidays"}) if g.name.startswith("archive")
)
BUNDESBANK_2026_URL = (
    "https://web.archive.org/web/20251126030213id_/https://www.bundesbank.de"
    "/resource/blob/764518/84d26dc9e3e0cc1e6e3042290df060ff/472B63F073F071307366337C94F8C870"
    "/feiertage-in-deutschland-1-data.pdf"
)
LIVE = {
    g.source_id: g.content for g in goldens({s.id for s in REGISTRY}) if g.name.startswith("live")
}


def moved_columbus_day(page: bytes) -> bytes:
    """Columbus Day 2027 moves from Monday 11 to Tuesday 12 October: a made-up change."""
    if b">October 11<" not in page:
        raise AssertionError("The fixture no longer has the 2027 Columbus Day cell")
    return page.replace(b">October 11<", b">October 12<", 1)


@dataclass
class World:
    clock: FakeClock
    database: Database
    bucket: storage.Bucket
    gcs_endpoint: str
    router: respx.MockRouter
    pages: dict[str, bytes]
    calendars: Mapping[str, CalendarDefinition]

    def fetcher(self, client: httpx.Client) -> Fetcher:
        return Fetcher(
            client,
            "test",
            self.clock,
            throttle=HostThrottle(sleep=lambda _: None),
            retry_wait=wait_none(),
        )

    def store(self) -> GcsSnapshotStore:
        return GcsSnapshotStore.connect(self.bucket.name, self.gcs_endpoint)

    def seed(self) -> None:
        with (
            new_client() as client,
            open_store(self.database.pipeline) as store,
            _connection(self.database.pipeline) as connection,
        ):
            Seeder(self.fetcher(client), self.store(), self.calendars, self.clock).run(
                REGISTRY,
                store,
                PostgresSeedStore(connection),
                SEED_YEARS,
                also={"bundesbank-holidays": [BUNDESBANK_2026_URL]},
            )

    def run_due(self) -> None:
        recorder = RecordingTelemetry()
        with new_client() as client:
            context = PipelineContext(
                registry=REGISTRY,
                calendars=self.calendars,
                open_store=partial(open_store, self.database.pipeline),
                checker=SourceChecker(
                    self.fetcher(client),
                    self.store(),
                    recorder.telemetry,
                    self.clock,
                    self.calendars,
                ),
                clock=self.clock,
            )
            connector = procrastinate.PsycopgConnector(
                conninfo=self.database.pipeline, min_size=1, max_size=1
            )
            run_due_blocking(create_app(connector, context), context, install_signal_handlers=False)

    def daily(self) -> DailyResult:
        with httpx.Client() as client:
            return DailyJob(
                open_review=partial(open_review_store, self.database.pipeline, self.calendars),
                open_releases=partial(_release_store, self.database.pipeline),
                run_cleanup=partial(run_cleanup, self.database.pipeline),
                releases=self.store(),
                dataset_repository=DatasetRepository(client, DATASET_REPOSITORY, "test-token"),
                backup=DatabaseBackup(
                    self.store(),
                    self.clock,
                    user="settlecal_backup",
                    password="unused",
                    databases=["settlecal"],
                    runner=_fake_pg_dump,
                ),
                telemetry=RecordingTelemetry().telemetry,
                clock=self.clock,
            ).run()

    def rows(self, sql: str, params: Sequence[object] = ()) -> list[tuple[object, ...]]:
        with psycopg.connect(self.database.admin) as connection:
            return connection.execute(sql, params).fetchall()

    def candidate(self, calendar_id: str, entry_date: date) -> int:
        [(candidate_id,)] = self.rows(
            "SELECT id FROM candidate_change WHERE calendar_id = %s AND entry_date = %s",
            (calendar_id, entry_date),
        )
        return int(str(candidate_id))


@contextmanager
def _connection(conninfo: str) -> Iterator[psycopg.Connection[TupleRow]]:
    with psycopg.connect(conninfo, autocommit=True, row_factory=tuple_row) as connection:
        yield connection


@contextmanager
def _release_store(conninfo: str) -> Iterator[PostgresReleaseStore]:
    with _connection(conninfo) as connection:
        yield PostgresReleaseStore(connection)


def _fake_pg_dump(args: Sequence[str], env: Mapping[str, str], output: Path) -> None:
    assert args[0] == "pg_dump"
    assert env["PGUSER"] == "settlecal_backup"
    output.write_bytes(b"PGDMP fake dump")


@pytest.fixture
def world(migrated: Database, bucket: storage.Bucket, gcs_endpoint: str) -> Iterator[World]:
    pages = dict(LIVE)
    with respx.mock(assert_all_called=False) as router:
        router.get("https://web.archive.org/robots.txt").respond(404)
        for source in REGISTRY:
            host = httpx.URL(source.url).host
            router.get(f"https://{host}/robots.txt").respond(404)
            document_url = source.url
            if source.document is not None:
                # The stable page links to the current edition, as the real one does.
                document_url = f"https://{host}{BUNDESBANK_PDF_PATH}"
                router.get(source.url).respond(
                    200,
                    html=f'<p><a href="{BUNDESBANK_PDF_PATH}">Download</a></p>',
                )
            router.get(document_url).mock(
                side_effect=lambda _request, source_id=source.id: httpx.Response(
                    200,
                    content=pages[source_id],
                    headers={"Content-Type": next(iter(REGISTRY.get(source_id).content_types))},
                )
            )
        router.get(BUNDESBANK_2026_URL).respond(
            200, content=BUNDESBANK_2026, headers={"Content-Type": "application/pdf"}
        )
        _mock_github(router)
        yield World(FakeClock(), migrated, bucket, gcs_endpoint, router, pages, load_catalog())


def _mock_github(router: respx.MockRouter) -> None:
    pulls: list[dict[str, object]] = []

    def list_pulls(_request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=pulls)

    def open_pull(request: httpx.Request) -> httpx.Response:
        pull = {
            "html_url": f"https://github.com/{DATASET_REPOSITORY}/pull/{len(pulls) + 1}",
            **json.loads(request.content),
        }
        pulls.append(pull)
        return httpx.Response(201, json=pull)

    github = partial(router.route, host=GITHUB_HOST)
    github(method="GET", path=f"{GITHUB_PATH}/pulls").mock(side_effect=list_pulls)
    github(method="GET", path=GITHUB_PATH).respond(200, json={"default_branch": "main"})
    github(method="GET", path=f"{GITHUB_PATH}/git/ref/heads/main").respond(
        200, json={"object": {"sha": "abc123"}}
    )
    github(method="POST", path=f"{GITHUB_PATH}/git/refs").respond(201, json={})
    github(method="PUT", path__startswith=f"{GITHUB_PATH}/contents/releases/").respond(201, json={})
    github(method="POST", path=f"{GITHUB_PATH}/pulls").mock(side_effect=open_pull)


def change_fed_pages(world: World) -> None:
    for source in (frbservices_holiday_schedules.SOURCE, federalreserve_k8.SOURCE):
        world.pages[source.id] = moved_columbus_day(LIVE[source.id])
    world.clock.advance(timedelta(days=1))


def test_fixture_change_flows_to_a_release_that_passes_conformance(world: World) -> None:
    world.seed()
    change_fed_pages(world)
    world.run_due()

    fedwire = world.candidate("US-FEDWIRE", MOVED_COLUMBUS_DAY)
    with open_review_store(world.database.pipeline, world.calendars) as review:
        assert review.approve(fedwire, REVIEW, world.clock()) is PublishResult.PUBLISHED
    result = world.daily()

    assert result.ok, result.failed
    release_id = result.release
    assert release_id == "2026.10.29.1"
    content = world.bucket.blob(release_object_key(release_id)).download_as_bytes()
    jsonschema.validate(json.loads(content), json.loads(RELEASE_SCHEMA.read_text(encoding="utf-8")))
    outcomes = run_cases(parse_release(content), load_cases(CASES))
    assert outcomes, "no conformance case applies to the release"
    assert [o for o in outcomes if not o.passed] == []


def test_seed_publishes_first_versions_with_their_basis(world: World) -> None:
    world.seed()

    assert world.rows(
        """
        SELECT calendar_id, basis, revision FROM calendar_entry
        WHERE entry_date = '2026-12-25' AND calendar_id IN ('US-FEDWIRE', 'GB-CHAPS')
        ORDER BY calendar_id
        """
    ) == [("GB-CHAPS", "projected", 1), ("US-FEDWIRE", "published", 1)]
    assert world.rows("SELECT count(*) FROM calendar_entry WHERE calendar_id = 'US-FEDNOW'") == [
        (0,)
    ]


def test_seed_twice_adds_nothing(world: World) -> None:
    world.seed()
    before = world.rows("SELECT count(*), max(revision) FROM calendar_entry")

    world.seed()

    assert world.rows("SELECT count(*), max(revision) FROM calendar_entry") == before


def test_change_becomes_corroborated_candidates(world: World) -> None:
    world.seed()
    change_fed_pages(world)

    world.run_due()

    assert world.rows(
        """
        SELECT calendar_id, corroboration, source_count, status FROM candidate_change
        WHERE entry_date = %s ORDER BY calendar_id
        """,
        (MOVED_COLUMBUS_DAY,),
    ) == [
        ("US-FEDACH", "corroborated", 2, "pending"),
        ("US-FEDWIRE", "corroborated", 2, "pending"),
    ]


def test_approval_publishes_entry_evidence_event_and_outbox(world: World) -> None:
    world.seed()
    change_fed_pages(world)
    world.run_due()
    fedwire = world.candidate("US-FEDWIRE", MOVED_COLUMBUS_DAY)

    with open_review_store(world.database.pipeline, world.calendars) as review:
        review.approve(fedwire, REVIEW, world.clock())

    [(entry_id, revision, basis)] = world.rows(
        """
        SELECT id, revision, basis FROM calendar_entry
        WHERE calendar_id = 'US-FEDWIRE' AND entry_date = %s AND upper_inf(recorded_during)
        """,
        (MOVED_COLUMBUS_DAY,),
    )
    assert (revision, basis) == (2, "published")
    assert world.rows("SELECT count(*) FROM entry_source WHERE entry_id = %s", (entry_id,)) == [
        (2,)
    ]
    [(event_id, change_type, notice)] = world.rows(
        "SELECT id, change_type, notice_days FROM change_event WHERE candidate_id = %s", (fedwire,)
    )
    assert (change_type, notice) == (
        "closure_added",
        (MOVED_COLUMBUS_DAY - world.clock().date()).days,
    )
    [(event_type, status, payload)] = world.rows(
        "SELECT event_type, status, payload FROM outbox WHERE change_event_id = %s", (event_id,)
    )
    assert (event_type, status) == ("closure_added", "pending")
    assert isinstance(payload, dict)
    assert (payload["calendar_id"], payload["date"], payload["revision"]) == (
        "US-FEDWIRE",
        "2027-10-12",
        2,
    )
    assert world.rows(
        "SELECT status, reviewer_subject, review_reason FROM candidate_change WHERE id = %s",
        (fedwire,),
    ) == [("published", REVIEW.reviewer, REVIEW.reason)]


def test_publishing_the_same_candidate_twice_is_a_no_op(world: World) -> None:
    world.seed()
    change_fed_pages(world)
    world.run_due()
    fedwire = world.candidate("US-FEDWIRE", MOVED_COLUMBUS_DAY)
    with open_review_store(world.database.pipeline, world.calendars) as review:
        review.approve(fedwire, REVIEW, world.clock())
        counts = _publish_counts(world)

        again = review.approve(fedwire, REVIEW, world.clock())
        published_again = review.publish(fedwire, world.clock())

    assert (again, published_again) == (PublishResult.ALREADY_PUBLISHED,) * 2
    assert _publish_counts(world) == counts


def test_daily_publishes_candidates_approved_elsewhere(world: World) -> None:
    world.seed()
    change_fed_pages(world)
    world.run_due()
    fedach = world.candidate("US-FEDACH", MOVED_COLUMBUS_DAY)
    # What the web review screen will do: approve, and leave publishing to the job.
    with psycopg.connect(world.database.admin, autocommit=True) as connection:
        connection.execute(
            """
            UPDATE candidate_change SET status = 'approved', reviewer_subject = 'web:subject',
                   review_reason = 'Approved on the web', reviewed_at = now()
            WHERE id = %s
            """,
            (fedach,),
        )

    result = world.daily()

    assert result.published == [fedach]
    assert world.rows("SELECT status FROM candidate_change WHERE id = %s", (fedach,)) == [
        ("published",)
    ]


def test_daily_without_changes_cuts_no_new_release(world: World) -> None:
    world.seed()
    first = world.daily()
    world.clock.advance(timedelta(days=1))

    second = world.daily()

    assert (first.release, second.release) == ("2026.10.28.1", None)
    assert second.ok, second.failed
    assert world.rows("SELECT count(*) FROM dataset_release") == [(1,)]
    assert second.pull_request == first.pull_request


def test_daily_records_release_checksum_and_backup(world: World) -> None:
    world.seed()

    result = world.daily()

    assert result.release is not None
    content = world.bucket.blob(release_object_key(result.release)).download_as_bytes()
    [(stored,)] = world.rows(
        "SELECT export_sha256 FROM dataset_release WHERE id = %s", (result.release,)
    )
    assert isinstance(stored, bytes)
    assert stored.hex() == hashlib.sha256(content).hexdigest()
    assert result.backups == ["pg_dump/settlecal/20261028T060000Z.dump"]
    assert result.pull_request is not None


def test_rejecting_publishes_nothing(world: World) -> None:
    world.seed()
    change_fed_pages(world)
    world.run_due()
    fedwire = world.candidate("US-FEDWIRE", MOVED_COLUMBUS_DAY)
    counts = _publish_counts(world)

    with open_review_store(world.database.pipeline, world.calendars) as review:
        review.reject(
            fedwire, Review(reviewer="cli:test-reviewer", reason="Looks wrong"), world.clock()
        )

    assert _publish_counts(world) == counts
    assert world.rows("SELECT status FROM candidate_change WHERE id = %s", (fedwire,)) == [
        ("rejected",)
    ]


def test_review_list_puts_urgent_candidates_first(world: World) -> None:
    world.seed()
    change_fed_pages(world)
    world.run_due()
    with psycopg.connect(world.database.admin, autocommit=True) as connection:
        connection.execute(
            """
            INSERT INTO candidate_change (calendar_id, entry_date, after_state)
            VALUES ('GB-EAW', %s, '{"is_open": false, "reason": "Short-notice holiday"}')
            """,
            (world.clock().date() + timedelta(days=10),),
        )

    with open_review_store(world.database.pipeline, world.calendars) as review:
        listed = review.list_candidates(world.clock().date())

    assert (listed[0].calendar_id, listed[0].notice_days < 14) == ("GB-EAW", True)


def _publish_counts(world: World) -> list[tuple[object, ...]]:
    return world.rows(
        """
        SELECT (SELECT count(*) FROM calendar_entry), (SELECT count(*) FROM entry_source),
               (SELECT count(*) FROM change_event), (SELECT count(*) FROM outbox),
               (SELECT max(current_revision) FROM calendar)
        """
    )
