"""OpenTelemetry traces and metrics, exported to Grafana Cloud over OTLP when configured.

The job is short-lived, so ``shutdown`` must run before exit to flush what was recorded.
"""

from collections.abc import Callable
from dataclasses import dataclass

from opentelemetry.exporter.otlp.proto.http.metric_exporter import OTLPMetricExporter
from opentelemetry.exporter.otlp.proto.http.trace_exporter import OTLPSpanExporter
from opentelemetry.metrics import Counter, MeterProvider

# The synchronous gauge is stable in the SDK but still exported under this name.
from opentelemetry.metrics import _Gauge as Gauge
from opentelemetry.sdk.metrics import MeterProvider as SdkMeterProvider
from opentelemetry.sdk.metrics.export import PeriodicExportingMetricReader
from opentelemetry.sdk.resources import Resource
from opentelemetry.sdk.trace import TracerProvider as SdkTracerProvider
from opentelemetry.sdk.trace.export import BatchSpanProcessor
from opentelemetry.trace import Tracer, TracerProvider

INSTRUMENTATION_NAME = "zeitwide_pipeline"
SOURCE_ATTRIBUTE = "zeitwide.source"


@dataclass(frozen=True, slots=True)
class Telemetry:
    tracer: Tracer
    # Seconds since the epoch of each source's last successful check. Alert on age.
    last_success: Gauge
    parse_failures: Counter
    # 1 while a source is suspended after repeated failures. Alert when it is 1.
    suspended: Gauge

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
        )


def configure_telemetry(*, export: bool) -> tuple[Telemetry, Callable[[], None]]:
    """Exporters read OTEL_EXPORTER_OTLP_* and OTEL_SERVICE_NAME from the environment."""
    resource = Resource.create()
    tracer_provider = SdkTracerProvider(resource=resource)
    readers = []
    if export:
        tracer_provider.add_span_processor(BatchSpanProcessor(OTLPSpanExporter()))
        readers.append(PeriodicExportingMetricReader(OTLPMetricExporter()))
    meter_provider = SdkMeterProvider(resource=resource, metric_readers=readers)

    def shutdown() -> None:
        tracer_provider.shutdown()
        meter_provider.shutdown()

    return Telemetry.create(tracer_provider, meter_provider), shutdown


def source_attributes(source_id: str) -> dict[str, str]:
    return {SOURCE_ATTRIBUTE: source_id}
