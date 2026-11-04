import pytest
from opentelemetry import trace
from opentelemetry.context import Context
from opentelemetry.sdk.trace import TracerProvider
from opentelemetry.sdk.trace.export import SimpleSpanProcessor
from opentelemetry.sdk.trace.export.in_memory_span_exporter import InMemorySpanExporter
from opentelemetry.trace import NonRecordingSpan, SpanContext, Status, StatusCode, TraceFlags

from zeitwide_pipeline.telemetry import ErrorKeepingSpanProcessor, is_trace_sampled

KEPT = 0x0123456789ABCDEF0000000000000001
DROPPED = 0x0123456789ABCDEFFFFFFFFFFFFFFFFF


# The same IDs and answers as TraceIdRatioTest.java and web's telemetry.test.ts.
@pytest.mark.parametrize(
    ("trace_id", "ratio", "sampled"),
    [
        (0xFFFFFFFFFFFFFFFF0000000000000000, 0.1, True),
        (0x0000000000000000199999999999A000, 0.1, False),
        (0x00000000000000001999999999999800, 0.1, True),
        (0x0000000000000000FFFFFFFFFFFFFFFF, 0.1, False),
        (0x0000000000000000FFFFFFFFFFFFFFFF, 1, True),
        (0xFFFFFFFFFFFFFFFF0000000000000000, 0, False),
    ],
)
def test_should_decide_from_the_lower_half_of_the_trace_id(
    trace_id: int, ratio: float, sampled: bool
) -> None:
    assert is_trace_sampled(trace_id, ratio) is sampled


@pytest.fixture
def exported() -> InMemorySpanExporter:
    return InMemorySpanExporter()


def _tracer(
    exported: InMemorySpanExporter, ratio: float, max_pending: int = 16
) -> tuple[trace.Tracer, ErrorKeepingSpanProcessor]:
    processor = ErrorKeepingSpanProcessor(SimpleSpanProcessor(exported), ratio, max_pending)
    provider = TracerProvider()
    provider.add_span_processor(processor)
    return provider.get_tracer("test"), processor


def _remote_parent(trace_id: int) -> Context:
    parent = SpanContext(trace_id, 0x00F067AA0BA902B7, is_remote=True, trace_flags=TraceFlags(1))
    return trace.set_span_in_context(NonRecordingSpan(parent))


def _root_with_child(tracer: trace.Tracer, trace_id: int, *, child_fails: bool) -> None:
    with (
        tracer.start_as_current_span("root", context=_remote_parent(trace_id)),
        tracer.start_as_current_span("child") as child,
    ):
        if child_fails:
            child.set_status(Status(StatusCode.ERROR))


def _names(exported: InMemorySpanExporter) -> list[str]:
    return [span.name for span in exported.get_finished_spans()]


def test_should_keep_a_whole_trace_in_the_sampled_fraction(exported: InMemorySpanExporter) -> None:
    tracer, _ = _tracer(exported, 0.1)
    _root_with_child(tracer, KEPT, child_fails=False)
    assert _names(exported) == ["child", "root"]


def test_should_drop_a_trace_outside_the_fraction(exported: InMemorySpanExporter) -> None:
    tracer, _ = _tracer(exported, 0.1)
    _root_with_child(tracer, DROPPED, child_fails=False)
    assert _names(exported) == []


def test_should_keep_a_failed_trace_outside_the_fraction(exported: InMemorySpanExporter) -> None:
    tracer, _ = _tracer(exported, 0.1)
    _root_with_child(tracer, DROPPED, child_fails=True)
    assert _names(exported) == ["child", "root"]


def test_should_keep_a_pipeline_check_whose_exception_escaped(
    exported: InMemorySpanExporter,
) -> None:
    tracer, _ = _tracer(exported, 0)
    with pytest.raises(RuntimeError), tracer.start_as_current_span("source.check"):
        raise RuntimeError("parser broke")
    assert _names(exported) == ["source.check"]


def test_should_export_a_span_ending_after_its_kept_root(exported: InMemorySpanExporter) -> None:
    tracer, _ = _tracer(exported, 0.1)
    root = tracer.start_span("root", context=_remote_parent(KEPT))
    late = tracer.start_span("late", context=trace.set_span_in_context(root))
    root.end()
    late.end()
    assert _names(exported) == ["root", "late"]


def test_should_decide_the_oldest_trace_once_too_many_are_pending(
    exported: InMemorySpanExporter,
) -> None:
    tracer, _ = _tracer(exported, 1, max_pending=1)
    first = tracer.start_span("first-root", context=_remote_parent(KEPT))
    second = tracer.start_span("second-root", context=_remote_parent(DROPPED))
    tracer.start_span("first-child", context=trace.set_span_in_context(first)).end()
    tracer.start_span("second-child", context=trace.set_span_in_context(second)).end()
    assert _names(exported) == ["first-child"]


def test_should_release_pending_spans_on_flush_and_shutdown(
    exported: InMemorySpanExporter,
) -> None:
    tracer, processor = _tracer(exported, 1)
    root = tracer.start_span("root", context=_remote_parent(KEPT))
    tracer.start_span("child", context=trace.set_span_in_context(root)).end()

    assert processor.force_flush()
    processor.shutdown()

    assert _names(exported) == ["child"]


def test_should_reject_a_ratio_outside_zero_to_one(exported: InMemorySpanExporter) -> None:
    with pytest.raises(ValueError, match="between 0 and 1"):
        ErrorKeepingSpanProcessor(SimpleSpanProcessor(exported), 1.5)
