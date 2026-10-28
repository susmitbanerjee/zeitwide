"""Test doubles: a settable clock, in-memory storage, and in-memory telemetry."""

from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime, timedelta

from opentelemetry.sdk.metrics import MeterProvider
from opentelemetry.sdk.metrics.export import InMemoryMetricReader
from opentelemetry.sdk.trace import ReadableSpan, TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter

from settlecal_pipeline.telemetry import SOURCE_ATTRIBUTE, Telemetry

START = datetime(2026, 10, 28, 6, 0, tzinfo=UTC)


@dataclass
class FakeClock:
    now: datetime = START

    def __call__(self) -> datetime:
        return self.now

    def advance(self, by: timedelta) -> None:
        self.now += by


@dataclass
class MemorySnapshotStore:
    objects: dict[str, tuple[bytes, str]] = field(default_factory=dict)

    def put(self, key: str, content: bytes, content_type: str) -> None:
        self.objects.setdefault(key, (content, content_type))


class RecordingTelemetry:
    """Real SDK providers with in-memory exporters, so tests read what was recorded."""

    def __init__(self) -> None:
        self.spans = InMemorySpanExporter()
        tracer_provider = TracerProvider()
        tracer_provider.add_span_processor(SimpleSpanProcessor(self.spans))
        self.metrics = InMemoryMetricReader()
        self.telemetry = Telemetry.create(
            tracer_provider, MeterProvider(metric_readers=[self.metrics])
        )

    def finished_spans(self) -> Sequence[ReadableSpan]:
        return self.spans.get_finished_spans()

    def span_names(self) -> list[str]:
        return [span.name for span in self.finished_spans()]

    def metric(self, name: str, source_id: str) -> float | None:
        """The latest value of a metric for one source, or None if never recorded."""
        data = self.metrics.get_metrics_data()
        if data is None:
            return None
        for resource_metrics in data.resource_metrics:
            for scope_metrics in resource_metrics.scope_metrics:
                for metric in scope_metrics.metrics:
                    if metric.name != name:
                        continue
                    for point in metric.data.data_points:
                        if point.attributes and point.attributes.get(SOURCE_ATTRIBUTE) == source_id:
                            return float(point.value)  # type: ignore[union-attr]
        return None
