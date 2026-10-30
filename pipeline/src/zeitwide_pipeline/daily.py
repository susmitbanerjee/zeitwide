"""The daily Cloud Run job: publish approved changes, release, back up, clean up.

Each step runs even if an earlier one failed, so one broken step can't stop the backup;
the job still exits with an error so the failure alerts.
"""

from collections.abc import Callable
from contextlib import AbstractContextManager
from dataclasses import dataclass, field

import structlog

from zeitwide_pipeline.backup import DatabaseBackup
from zeitwide_pipeline.clock import Clock
from zeitwide_pipeline.github import DatasetRepository
from zeitwide_pipeline.release import (
    PostgresReleaseStore,
    ReleaseExport,
    next_release_id,
    revisions_changed,
)
from zeitwide_pipeline.review_db import PostgresReviewStore
from zeitwide_pipeline.storage import SnapshotStore
from zeitwide_pipeline.telemetry import Telemetry

log = structlog.get_logger(__name__)


class ReleaseMismatchError(Exception):
    """Re-exporting a release gave different bytes from the checksum recorded for it."""


@dataclass(frozen=True)
class DailyJob:
    open_review: Callable[[], AbstractContextManager[PostgresReviewStore]]
    open_releases: Callable[[], AbstractContextManager[PostgresReleaseStore]]
    run_cleanup: Callable[[], object]
    releases: SnapshotStore
    dataset_repository: DatasetRepository | None
    backup: DatabaseBackup | None
    telemetry: Telemetry
    clock: Clock

    def run(self) -> "DailyResult":
        result = DailyResult()
        steps: list[tuple[str, Callable[[DailyResult], None]]] = [
            ("daily.publish_approved", self._publish_approved),
            ("daily.release", self._release),
            ("daily.backup", self._backup),
            ("daily.cleanup", self._cleanup),
        ]
        for name, step in steps:
            with self.telemetry.tracer.start_as_current_span(name):
                try:
                    step(result)
                except Exception as error:
                    result.failed.append(name)
                    log.exception("daily_step_failed", step=name, error=str(error))
        return result

    def _publish_approved(self, result: "DailyResult") -> None:
        with self.open_review() as review:
            for candidate_id in review.approved_ids():
                review.publish(candidate_id, self.clock())
                result.published.append(candidate_id)

    def _release(self, result: "DailyResult") -> None:
        with self.open_releases() as releases:
            latest = releases.latest()
            now = self.clock()
            if revisions_changed(releases.revisions(now), latest):
                release_id = next_release_id(now.date(), releases.release_ids_on(now.date()))
                export = releases.export(release_id, now)
                releases.record(export)
                result.release = export.id
                log.info("release_cut", release=export.id, sha256=export.sha256)
            elif latest is not None:
                # Nothing new; finish publishing the latest release if an earlier run failed.
                export = releases.export(latest.id, latest.as_of)
                if export.sha256 != latest.export_sha256:
                    raise ReleaseMismatchError(
                        f"Release {latest.id} no longer exports the same bytes"
                    )
            else:
                return
        self._distribute(export, result)

    def _distribute(self, export: ReleaseExport, result: "DailyResult") -> None:
        self.releases.put(export.object_key, export.content, "application/json")
        if self.dataset_repository is None:
            log.warning("release_pull_request_skipped", release=export.id, reason="not configured")
            return
        result.pull_request = self.dataset_repository.propose_release(export.id, export.content)

    def _backup(self, result: "DailyResult") -> None:
        if self.backup is None:
            log.warning("backup_skipped", reason="not configured")
            return
        result.backups = self.backup.run()

    def _cleanup(self, _result: "DailyResult") -> None:
        self.run_cleanup()


@dataclass
class DailyResult:
    published: list[int] = field(default_factory=list)
    release: str | None = None
    pull_request: str | None = None
    backups: list[str] = field(default_factory=list)
    failed: list[str] = field(default_factory=list)

    @property
    def ok(self) -> bool:
        return not self.failed
