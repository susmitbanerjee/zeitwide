"""Command-line entry point: ``zeitwide`` (also ``zeitwide-pipeline`` for the Cloud Run jobs).

Jobs: ``run-due`` (every 15 minutes) and ``daily``. People: ``review``, ``seed``,
``resume-source``, ``dry-run`` and ``save-fixture``.
"""

import json
import os
from collections.abc import Callable, Iterator
from contextlib import contextmanager
from functools import partial
from importlib.metadata import version
from pathlib import Path
from typing import Annotated

import httpx
import procrastinate
import psycopg
import structlog
import typer
from psycopg.rows import TupleRow, tuple_row

from zeitwide_pipeline.backup import DatabaseBackup
from zeitwide_pipeline.calendars import load_catalog
from zeitwide_pipeline.check import SourceChecker
from zeitwide_pipeline.clock import utc_now
from zeitwide_pipeline.daily import DailyJob
from zeitwide_pipeline.db import open_store, run_cleanup
from zeitwide_pipeline.dryrun import dry_run, save_fixture
from zeitwide_pipeline.fetch import Fetcher, new_client, user_agent
from zeitwide_pipeline.github import DatasetRepository
from zeitwide_pipeline.jobs import PipelineContext, create_app, run_due_blocking
from zeitwide_pipeline.logs import configure_logging
from zeitwide_pipeline.publish import URGENT_NOTICE_DAYS
from zeitwide_pipeline.release import PostgresReleaseStore
from zeitwide_pipeline.review_db import (
    CandidateDetail,
    CandidateSummary,
    PostgresReviewStore,
    Review,
    ReviewError,
    open_review_store,
)
from zeitwide_pipeline.seed import PostgresSeedStore, Seeder
from zeitwide_pipeline.settings import FetchSettings, Settings
from zeitwide_pipeline.site import SiteLinks, build_site_files
from zeitwide_pipeline.site.store import DirectorySiteFileStore, GcsSiteFileStore
from zeitwide_pipeline.site.validate import validate_site
from zeitwide_pipeline.sources import builtin_registry
from zeitwide_pipeline.storage import GcsSnapshotStore, gcs_client
from zeitwide_pipeline.telemetry import configure_telemetry

EXIT_FAILED = 1
EXIT_NOT_FOUND = 3
EXIT_REVIEW_ERROR = 4
SEED_YEARS = (2026, 2027)
MAX_LIST_LIMIT = 500

log = structlog.get_logger(__name__)

app = typer.Typer(no_args_is_help=True, add_completion=False)
review_app = typer.Typer(no_args_is_help=True, help="Review candidate changes.")
app.add_typer(review_app, name="review")

ReviewerOption = Annotated[
    str,
    typer.Option(
        envvar="ZEITWIDE_REVIEWER",
        help="Who decides; recorded with the decision (the web screen records their sign-in ID).",
    ),
]
ReasonOption = Annotated[str, typer.Option(help="Why; recorded with the decision.")]


@app.callback()
def _setup(ctx: typer.Context) -> None:
    if ctx.invoked_subcommand != "health":
        configure_logging()


@app.command()
def health() -> None:
    """Exit 0 when the pipeline package is importable."""
    typer.echo("ok")


@app.command("run-due")
def run_due_command() -> None:
    """Check every source that is due, then exit."""
    settings = _settings()
    telemetry, shutdown = configure_telemetry(export=settings.otlp_endpoint is not None)
    try:
        with new_client() as client:
            calendars = load_catalog()
            context = PipelineContext(
                registry=builtin_registry(),
                calendars=calendars,
                open_store=partial(open_store, settings.database_conninfo),
                checker=SourceChecker(
                    Fetcher(client, _user_agent(settings), utc_now),
                    _object_store(settings, settings.snapshot_bucket),
                    telemetry,
                    utc_now,
                    calendars,
                ),
                clock=utc_now,
            )
            connector = procrastinate.PsycopgConnector(
                conninfo=settings.database_conninfo,
                min_size=1,
                max_size=settings.db_pool_max_size - 1,
            )
            run_due_blocking(create_app(connector, context), context)
    finally:
        shutdown()


@app.command()
def daily(
    skip_backup: Annotated[bool, typer.Option(help="Skip pg_dump, for local runs.")] = False,
) -> None:
    """Publish approved changes, cut a release if anything changed, back up, clean up."""
    settings = _settings()
    telemetry, shutdown = configure_telemetry(export=settings.otlp_endpoint is not None)
    try:
        with httpx.Client() as client:
            job = DailyJob(
                open_review=partial(open_review_store, settings.database_conninfo, load_catalog()),
                open_releases=partial(_open_release_store, settings.database_conninfo),
                run_cleanup=partial(run_cleanup, settings.database_conninfo),
                releases=_object_store(settings, settings.snapshot_bucket),
                dataset_repository=_dataset_repository(settings, client),
                backup=None if skip_backup else _backup(settings),
                telemetry=telemetry,
                clock=utc_now,
                site_files=_site_file_store(settings),
                site_links=_site_links(settings),
            )
            result = job.run()
    finally:
        shutdown()
    log.info(
        "daily_finished",
        published=result.published,
        release=result.release,
        pull_request=result.pull_request,
        backups=result.backups,
        failed=result.failed,
    )
    if not result.ok:
        raise typer.Exit(EXIT_FAILED)


@app.command()
def seed(
    year: Annotated[list[int] | None, typer.Option(help="A year to seed; repeatable.")] = None,
    also: Annotated[
        list[str] | None,
        typer.Option(help="SOURCE_ID=URL of an earlier edition to read too; repeatable."),
    ] = None,
) -> None:
    """Publish each calendar's first versions from the sources, without review."""
    settings = _settings()
    with (
        new_client() as client,
        open_store(settings.database_conninfo) as store,
        _open_seed_store(settings.database_conninfo) as seed_store,
    ):
        seeder = Seeder(
            fetcher=Fetcher(client, _user_agent(settings), utc_now),
            snapshots=_object_store(settings, settings.snapshot_bucket),
            calendars=load_catalog(),
            clock=utc_now,
        )
        result = seeder.run(
            builtin_registry(), store, seed_store, frozenset(year or SEED_YEARS), _editions(also)
        )
    for calendar_id, count in sorted(result.published.items()):
        typer.echo(f"{calendar_id}: {count} entries")
    for calendar_id, conflict in result.conflicts:
        typer.echo(f"{calendar_id} {conflict}: sources disagree; left for review")


@app.command("resume-source")
def resume_source(source_id: str) -> None:
    """Check a suspended source again."""
    with open_store(_settings().database_conninfo) as store:
        if not store.resume(source_id):
            log.error("source_not_suspended", source_id=source_id)
            raise typer.Exit(EXIT_NOT_FOUND)
    log.info("source_resumed", source_id=source_id)


@app.command("site-files")
def site_files_command(
    release: Annotated[Path, typer.Option(help="A release JSON file, as the daily job exports.")],
    out: Annotated[Path, typer.Option(help="Folder to write into.")],
    site_url: Annotated[str, typer.Option(help="The public site's origin.")],
    api_url: Annotated[str, typer.Option(help="The public API's origin.")],
) -> None:
    """Write a release's site files (llms.txt, sitemaps, robots.txt) into a folder."""
    document = json.loads(release.read_text(encoding="utf-8"))
    files = build_site_files(document, SiteLinks(site_url, api_url))
    DirectorySiteFileStore(out).publish(str(document["release"]), files)
    log.info("site_files_written", out=str(out), files=len(files))


@app.command("validate-site")
def validate_site_command(
    site_url: Annotated[str, typer.Option(help="The site to check, such as https://zeitwide.com.")],
    api_url: Annotated[str, typer.Option(help="Its API, for the closure counts.")],
) -> None:
    """Check llms.txt, llms-full.txt and the sitemaps on a live site; exit 1 on any problem."""
    headers = (
        {"Authorization": f"Bearer {key}"} if (key := os.environ.get("ZEITWIDE_API_KEY")) else {}
    )
    with httpx.Client(timeout=httpx.Timeout(30, connect=5), headers=headers) as client:
        problems = validate_site(client, site_url, api_url, log=typer.echo)
    for problem in problems:
        typer.echo(f"FAIL {problem}")
    if problems:
        raise typer.Exit(EXIT_FAILED)
    typer.echo("ok: llms.txt, llms-full.txt and the sitemaps are valid")


@app.command("dry-run")
def dry_run_command(
    source_ids: Annotated[list[str] | None, typer.Argument(help="Sources; default all.")] = None,
) -> None:
    """Fetch and parse live sources; touch no database or storage."""
    registry = builtin_registry()
    settings = _fetch_settings()
    with new_client() as client:
        fetcher = Fetcher(client, _user_agent(settings), utc_now)
        sources = [registry.get(source_id) for source_id in source_ids or []] or list(registry)
        results = dry_run(sources, fetcher, load_catalog())
    for result in results:
        typer.echo(result.to_json())
    if not all(result.ok for result in results):
        raise typer.Exit(EXIT_FAILED)


@app.command("save-fixture")
def save_fixture_command(
    source_id: str,
    name: Annotated[str, typer.Argument(help="File name without extension.")],
    url: Annotated[str | None, typer.Option(help="Fetch this URL instead.")] = None,
    out: Annotated[Path, typer.Option()] = Path("tests/fixtures"),
) -> None:
    """Store a real snapshot for golden tests."""
    settings = _fetch_settings()
    source = builtin_registry().get(source_id)
    with new_client() as client:
        fetcher = Fetcher(client, _user_agent(settings), utc_now)
        path = save_fixture(fetcher, source, name, out, clock=utc_now, url=url)
    log.info("fixture_saved", path=str(path))


@review_app.command("list")
def review_list(
    limit: Annotated[int, typer.Option(min=1, max=MAX_LIST_LIMIT)] = 50,
    status: Annotated[str, typer.Option(help="pending, approved, rejected or published.")] = (
        "pending"
    ),
) -> None:
    """Candidate changes, urgent first (under 14 days' notice)."""
    with _review_store() as store:
        candidates = store.list_candidates(utc_now().date(), status=status, limit=limit)
    if not candidates:
        typer.echo(f"No {status} candidates.")
    for candidate in candidates:
        typer.echo(_summary_line(candidate))


@review_app.command("show")
def review_show(candidate_id: int) -> None:
    """One candidate: before and after, and each source snapshot behind it."""
    settings = _settings()
    with _review_store(settings) as store:
        detail = _or_exit(lambda: store.candidate(candidate_id, utc_now().date()))
    typer.echo(_detail_text(detail, settings.snapshot_bucket))


@review_app.command("approve")
def review_approve(candidate_id: int, reason: ReasonOption, reviewer: ReviewerOption) -> None:
    """Approve and publish a candidate in one transaction."""
    decision = _review(reviewer, reason)
    with _review_store() as store:
        result = _or_exit(lambda: store.approve(candidate_id, decision, utc_now()))
    typer.echo(f"Candidate {candidate_id}: {result.value}")


@review_app.command("reject")
def review_reject(candidate_id: int, reason: ReasonOption, reviewer: ReviewerOption) -> None:
    """Reject a candidate; nothing is published."""
    decision = _review(reviewer, reason)
    with _review_store() as store:
        _or_exit(lambda: store.reject(candidate_id, decision, utc_now()))
    typer.echo(f"Candidate {candidate_id}: rejected")


def main() -> None:
    app()


def _settings() -> Settings:
    return Settings()  # type: ignore[call-arg] # pydantic-settings reads the environment


def _fetch_settings() -> FetchSettings:
    return FetchSettings()  # type: ignore[call-arg] # pydantic-settings reads the environment


def _user_agent(settings: FetchSettings) -> str:
    return user_agent(version("zeitwide-pipeline"), str(settings.bot_contact_url))


def _site_file_store(settings: Settings) -> GcsSiteFileStore | None:
    if settings.site_files_bucket is None:
        return None
    return GcsSiteFileStore(gcs_client(settings.gcs_endpoint), settings.site_files_bucket)


def _site_links(settings: Settings) -> SiteLinks | None:
    if settings.site_url is None or settings.api_public_url is None:
        return None
    return SiteLinks(str(settings.site_url).rstrip("/"), str(settings.api_public_url).rstrip("/"))


def _object_store(settings: Settings, bucket: str) -> GcsSnapshotStore:
    return GcsSnapshotStore.connect(bucket, settings.gcs_endpoint)


@contextmanager
def _review_store(settings: Settings | None = None) -> Iterator[PostgresReviewStore]:
    conninfo = (settings or _settings()).database_conninfo
    with open_review_store(conninfo, load_catalog()) as store:
        yield store


@contextmanager
def _connect(conninfo: str) -> Iterator[psycopg.Connection[TupleRow]]:
    with psycopg.connect(conninfo, autocommit=True, row_factory=tuple_row) as connection:
        yield connection


@contextmanager
def _open_release_store(conninfo: str) -> Iterator[PostgresReleaseStore]:
    with _connect(conninfo) as connection:
        yield PostgresReleaseStore(connection)


@contextmanager
def _open_seed_store(conninfo: str) -> Iterator[PostgresSeedStore]:
    with _connect(conninfo) as connection:
        yield PostgresSeedStore(connection)


def _dataset_repository(settings: Settings, client: httpx.Client) -> DatasetRepository | None:
    if settings.dataset_repository is None or settings.github_token is None:
        return None
    token = settings.github_token.get_secret_value()
    return DatasetRepository(client, settings.dataset_repository, token)


def _backup(settings: Settings) -> DatabaseBackup | None:
    if not (settings.backup_bucket and settings.backup_db_user and settings.backup_db_password):
        return None
    return DatabaseBackup(
        _object_store(settings, settings.backup_bucket),
        utc_now,
        user=settings.backup_db_user,
        password=settings.backup_db_password.get_secret_value(),
        databases=settings.databases_to_back_up(),
    )


def _editions(also: list[str] | None) -> dict[str, list[str]]:
    editions: dict[str, list[str]] = {}
    for item in also or []:
        source_id, separator, url = item.partition("=")
        if not separator or not url.startswith("https://"):
            typer.echo(f"--also takes SOURCE_ID=https://..., not {item!r}", err=True)
            raise typer.Exit(EXIT_FAILED)
        editions.setdefault(source_id, []).append(url)
    return editions


def _review(reviewer: str, reason: str) -> Review:
    try:
        return Review(reviewer=reviewer, reason=reason)
    except ValueError as error:
        typer.echo(str(error), err=True)
        raise typer.Exit(EXIT_REVIEW_ERROR) from error


def _or_exit[T](action: Callable[[], T]) -> T:
    try:
        return action()
    except ReviewError as error:
        typer.echo(str(error), err=True)
        raise typer.Exit(EXIT_REVIEW_ERROR) from error


def _state(state: object) -> str:
    if not isinstance(state, dict):
        return "no entry"
    status = "open" if state.get("is_open") else "closed"
    basis = f", {state['basis']}" if state.get("basis") else ""
    return f"{status} ({state.get('reason')}{basis})"


def _summary_line(candidate: CandidateSummary) -> str:
    urgent = "URGENT " if candidate.notice_days < URGENT_NOTICE_DAYS else ""
    return (
        f"{urgent}#{candidate.id} {candidate.calendar_id} {candidate.entry_date} "
        f"in {candidate.notice_days} days: {_state(candidate.before)} -> "
        f"{_state(candidate.after)} [{candidate.corroboration}, {candidate.source_count} sources]"
    )


def _detail_text(detail: CandidateDetail, bucket: str) -> str:
    current = detail.current.to_json() if detail.current else "no entry"
    lines = [
        _summary_line(detail.summary),
        f"Status: {detail.summary.status}",
        f"Published now: {_state(current)}",
    ]
    if detail.reviewer:
        lines.append(f"Reviewed by {detail.reviewer}: {detail.review_reason}")
    lines.append(f"Snapshots ({len(detail.snapshots)}):")
    for snapshot in detail.snapshots:
        status = "open" if snapshot.is_open else "closed"
        lines.extend(
            [
                f"  {snapshot.source_id} ({snapshot.source_url})",
                f"    fetched {snapshot.fetched_at.isoformat()}, sha256 {snapshot.sha256}",
                f"    gs://{bucket}/{snapshot.object_key}",
                f"    says: {status}, {snapshot.reason}",
            ]
        )
    return "\n".join(lines)
