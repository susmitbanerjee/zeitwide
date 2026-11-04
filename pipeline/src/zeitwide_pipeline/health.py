"""Source freshness and review-queue gauges, read from the database on every run.

Reported from stored state rather than from the checks that ran, because a run checks
only the sources that are due: a daily source would otherwise report once a day, and a
suspended one never.
"""

from collections.abc import Iterable, Mapping
from datetime import datetime

from zeitwide_pipeline.db import ReviewQueue, SourceHealth
from zeitwide_pipeline.sources import Source
from zeitwide_pipeline.telemetry import Telemetry, source_attributes


def report_health(
    telemetry: Telemetry,
    sources: Iterable[Source],
    health: Mapping[str, SourceHealth],
    queue: ReviewQueue,
    now: datetime,
) -> None:
    for source in sources:
        state = health.get(source.id)
        if state is None:
            continue
        attributes = source_attributes(source.id)
        telemetry.schedule.set(source.schedule.total_seconds(), attributes)
        telemetry.consecutive_failures.set(state.consecutive_failures, attributes)
        telemetry.suspended.set(1 if state.suspended else 0, attributes)
        if state.last_success_at is not None:
            telemetry.last_success.set(state.last_success_at.timestamp(), attributes)
    _report_queue(telemetry, queue, now)


def _report_queue(telemetry: Telemetry, queue: ReviewQueue, now: datetime) -> None:
    telemetry.candidates_pending.set(queue.pending)
    # With nothing pending there is no age or notice; the alerts treat no data as fine.
    if queue.oldest_created_at is not None:
        telemetry.oldest_candidate_age.set((now - queue.oldest_created_at).total_seconds())
    if queue.soonest_entry_date is not None:
        telemetry.min_notice.set((queue.soonest_entry_date - now.date()).days)
