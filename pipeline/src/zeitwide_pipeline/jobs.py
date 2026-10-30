"""Procrastinate jobs. The Cloud Run job defers what is due, works the queue empty, and exits."""

import asyncio
from collections.abc import Callable, Iterable, Mapping
from contextlib import AbstractContextManager
from dataclasses import dataclass
from datetime import datetime

import procrastinate
import structlog
from procrastinate.exceptions import AlreadyEnqueued

from zeitwide_pipeline.calendars import CalendarDefinition
from zeitwide_pipeline.check import CheckResult, SourceChecker
from zeitwide_pipeline.clock import Clock
from zeitwide_pipeline.db import PipelineStore, RegisteredSource, SourceState
from zeitwide_pipeline.schedule import is_due
from zeitwide_pipeline.sources import Source, SourceRegistry

QUEUE = "sources"
CHECK_SOURCE_TASK = "check_source"

log = structlog.get_logger(__name__)

type StoreFactory = Callable[[], AbstractContextManager[PipelineStore]]


@dataclass(frozen=True, slots=True)
class PipelineContext:
    registry: SourceRegistry
    calendars: Mapping[str, CalendarDefinition]
    open_store: StoreFactory
    checker: SourceChecker
    clock: Clock


def create_app(
    connector: procrastinate.PsycopgConnector, context: PipelineContext
) -> procrastinate.App:
    app = procrastinate.App(connector=connector)

    # Failures are counted per source in source_state, so Procrastinate never retries.
    @app.task(name=CHECK_SOURCE_TASK, queue=QUEUE, retry=False)
    def check_source(source_id: str) -> str:
        source = context.registry.get(source_id)
        with context.open_store() as store:
            registered = store.find_source(source_id)
            if registered is None or not registered.enabled:
                log.warning("source_not_enabled", source_id=source_id)
                return CheckResult.SKIPPED.value
            return context.checker.check(source, registered.db_id, store).value

    return app


def due_sources(
    sources: Iterable[Source],
    registered: Mapping[str, RegisteredSource],
    states: Mapping[str, SourceState],
    now: datetime,
) -> list[str]:
    """Enabled, unsuspended sources whose schedule has come round."""
    due: list[str] = []
    for source in sources:
        entry = registered.get(source.id)
        if entry is None or not entry.enabled:
            continue
        state = states.get(source.id)
        if state is not None and state.suspended_at is not None:
            continue
        if is_due(state.last_checked_at if state else None, source.schedule, now):
            due.append(source.id)
    return due


async def run_due(
    app: procrastinate.App, context: PipelineContext, *, install_signal_handlers: bool = True
) -> list[str]:
    """Registers sources, defers each due check once, and works until the queue is empty."""
    with context.open_store() as store:
        store.register_calendars(context.calendars.values())
        registered = store.register_sources(context.registry)
        states = store.load_states()
    due = due_sources(context.registry, registered, states, context.clock())
    task = app.tasks[CHECK_SOURCE_TASK]
    async with app.open_async():
        for source_id in due:
            try:
                # lock: one check of a source runs at a time; queueing_lock: one waits.
                await task.configure(lock=source_id, queueing_lock=source_id).defer_async(
                    source_id=source_id
                )
            except AlreadyEnqueued:
                log.info("source_check_already_queued", source_id=source_id)
        await app.run_worker_async(
            queues=[QUEUE],
            wait=False,
            listen_notify=False,
            concurrency=1,
            install_signal_handlers=install_signal_handlers,
        )
    log.info("run_due_finished", deferred=len(due))
    return due


def run_due_blocking(
    app: procrastinate.App, context: PipelineContext, *, install_signal_handlers: bool = True
) -> list[str]:
    """``run_due`` on a selector event loop: psycopg's async mode can't use the Proactor
    loop that Windows defaults to. Linux, as on Cloud Run, uses a selector loop anyway."""
    return asyncio.run(
        run_due(app, context, install_signal_handlers=install_signal_handlers),
        loop_factory=asyncio.SelectorEventLoop,
    )
