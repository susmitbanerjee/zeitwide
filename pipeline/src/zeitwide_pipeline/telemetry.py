"""OpenTelemetry traces and metrics, exported to Grafana Cloud over OTLP when configured.

The job is short-lived, so ``shutdown`` must run before exit to flush what was recorded.
"""

import threading
from collections import OrderedDict
from collections.abc import Callable
from dataclasses import dataclass

from opentelemetry.context import Context
from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.metrics import Counter, MeterProvider

# The synchronous gauge is stable in the SDK but still exported under this name.
from opentelemetry.metrics import _Gauge as Gauge
from opentelemetry.sdk.metrics import MeterProvider as SdkMeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import ReadableSpan, SpanProcessor
from opentelemetry.sdk.trace import Span as SdkSpan
from opentelemetry.sdk.trace import TracerProvider as SdkTracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import StatusCode, Tracer, TracerProvider

INSTRUMENTATION_NAME = "zeitwide_pipeline"
SOURCE_ATTRIBUTE = "zeitwide.source"
DEFAULT_SAMPLE_RATIO = 0.1
_LOWER_HALF_MASK = (1 << 64) - 1
_DROPPED_LOW_BITS = 11
_TWO_TO_THE_53 = float(1 << 53)


@dataclass(frozen=True, slots=True)
class Telemetry:
    tracer: Tracer
    # Seconds since the epoch of each source's last successful check. Alert on age.
    last_success: Gauge
    parse_failures: Counter
    # 1 while a source is suspended after repeated failures. Alert when it is 1.
    suspended: Gauge
    # Failed checks in a row; parse failures count, and so do fetch failures.
    consecutive_failures: Gauge
    # How often each source is meant to be checked, so freshness alerts scale with it.
    schedule: Gauge
    candidates_pending: Gauge
    oldest_candidate_age: Gauge
    # Days until the soonest date a pending candidate would change.
    min_notice: Gauge

    @classmethod
    def create(cls, tracer_provider: TracerProvider, meter_provider: MeterProvider) -> "Telemetry":
        meter = meter_provider.get_meter(INSTRUMENTATION_NAME)
        return cls(
            tracer=tracer_provider.get_tracer(INSTRUMENTATION_NAME),
            last_success=meter.create_gauge(
                "zeitwide.source.last_success_timestamp",
                unit="s",
                description="When each source was last checked successfully",
            ),
            parse_failures=meter.create_counter(
                "zeitwide.source.parse_failures",
                description="Snapshots a source's parser rejected",
            ),
            suspended=meter.create_gauge(
                "zeitwide.source.suspended",
                description="1 when a source stopped being checked after repeated failures",
            ),
            consecutive_failures=meter.create_gauge(
                "zeitwide.source.consecutive_failures",
                description="Failed checks of a source in a row",
            ),
            schedule=meter.create_gauge(
                "zeitwide.source.schedule",
                unit="s",
                description="How often a source is meant to be checked",
            ),
            candidates_pending=meter.create_gauge(
                "zeitwide.review.candidates_pending",
                description="Candidate changes waiting for review",
            ),
            oldest_candidate_age=meter.create_gauge(
                "zeitwide.review.oldest_candidate_age",
                unit="s",
                description="How long the oldest pending candidate has waited",
            ),
            min_notice=meter.create_gauge(
                "zeitwide.review.min_notice",
                unit="d",
                description="Days until the soonest date a pending candidate would change",
            ),
        )


def is_trace_sampled(trace_id: int, ratio: float) -> bool:
    """Whether a trace is in the sampled fraction, from its ID alone.

    The web app and the API use the same rule (TraceIdRatio.java, trace-sampling.ts), so all
    three keep the same traces whole: the top 53 bits of the ID's lower half as a fraction.
    """
    return ((trace_id & _LOWER_HALF_MASK) >> _DROPPED_LOW_BITS) / _TWO_TO_THE_53 < ratio


class ErrorKeepingSpanProcessor(SpanProcessor):
    """Keeps a fixed fraction of traces plus every trace with an error.

    Every span is recorded; this holds a trace's spans until its local root ends, then passes
    them on to the exporting processor only if the trace is kept.
    """

    def __init__(self, exporter: SpanProcessor, ratio: float, max_pending: int = 2048) -> None:
        if not 0 <= ratio <= 1:
            raise ValueError("The sample ratio is between 0 and 1")
        self._exporter = exporter
        self._ratio = ratio
        self._max_pending = max_pending
        self._pending: OrderedDict[int, list[ReadableSpan]] = OrderedDict()
        self._decided: OrderedDict[int, bool] = OrderedDict()
        self._lock = threading.Lock()

    def on_start(self, span: SdkSpan, parent_context: Context | None = None) -> None:
        self._exporter.on_start(span, parent_context)

    def on_end(self, span: ReadableSpan) -> None:
        with self._lock:
            release = self._collect(span)
        for kept in release:
            self._exporter.on_end(kept)

    def force_flush(self, timeout_millis: int = 30000) -> bool:
        self._release_all()
        return self._exporter.force_flush(timeout_millis)

    def shutdown(self) -> None:
        self._release_all()
        self._exporter.shutdown()

    def _collect(self, span: ReadableSpan) -> list[ReadableSpan]:
        context = span.get_span_context()
        if context is None:
            return []
        trace_id = context.trace_id
        keep = self._decided.get(trace_id)
        if keep is not None:
            return [span] if keep else []
        self._pending.setdefault(trace_id, []).append(span)
        if span.parent is None or span.parent.is_remote:
            return self._decide(trace_id)
        if len(self._pending) > self._max_pending:
            return self._decide(next(iter(self._pending)))
        return []

    def _release_all(self) -> None:
        with self._lock:
            release = [span for trace_id in list(self._pending) for span in self._decide(trace_id)]
        for kept in release:
            self._exporter.on_end(kept)

    def _decide(self, trace_id: int) -> list[ReadableSpan]:
        spans = self._pending.pop(trace_id)
        failed = any(span.status.status_code is StatusCode.ERROR for span in spans)
        keep = failed or is_trace_sampled(trace_id, self._ratio)
        self._decided[trace_id] = keep
        if len(self._decided) > self._max_pending:
            self._decided.popitem(last=False)
        return spans if keep else []


def configure_telemetry(
    *, export: bool, sample_ratio: float = DEFAULT_SAMPLE_RATIO
) -> tuple[Telemetry, Callable[[], None]]:
    """Exporters read OTEL_EXPORTER_OTLP_* and OTEL_SERVICE_NAME from the environment."""
    resource = Resource.create()
    tracer_provider = SdkTracerProvider(resource=resource)
    readers = []
    if export:
        tracer_provider.add_span_processor(
            ErrorKeepingSpanProcessor(BatchSpanProcessor(OTLPSpanExporter()), sample_ratio)
        )
        readers.append(PeriodicExportingMetricReader(OTLPMetricExporter()))
    meter_provider = SdkMeterProvider(resource=resource, metric_readers=readers)

    def shutdown() -> None:
        tracer_provider.shutdown()
        meter_provider.shutdown()

    return Telemetry.create(tracer_provider, meter_provider), shutdown


def source_attributes(source_id: str) -> dict[str, str]:
    return {SOURCE_ATTRIBUTE: source_id}
