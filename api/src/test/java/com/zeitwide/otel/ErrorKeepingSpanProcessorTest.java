package com.zeitwide.otel;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.testing.exporter.InMemorySpanExporter;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.export.SimpleSpanProcessor;
import java.util.List;
import org.junit.jupiter.api.Test;

class ErrorKeepingSpanProcessorTest {

  private static final String KEPT_AT_TEN_PERCENT = "0123456789abcdef0000000000000001";
  private static final String DROPPED_AT_TEN_PERCENT = "0123456789abcdefffffffffffffffff";

  private final InMemorySpanExporter exported = InMemorySpanExporter.create();

  @Test
  void shouldKeepTheWholeTraceWhenItsIdIsInTheSampledFraction() {
    Tracer tracer = tracer(0.1, 16);

    rootWithChild(tracer, KEPT_AT_TEN_PERCENT, false);

    assertThat(names()).containsExactly("child", "root");
  }

  @Test
  void shouldDropATraceOutsideTheSampledFraction() {
    Tracer tracer = tracer(0.1, 16);

    rootWithChild(tracer, DROPPED_AT_TEN_PERCENT, false);

    assertThat(exported.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void shouldKeepATraceOutsideTheFractionWhenAnySpanFailed() {
    Tracer tracer = tracer(0.1, 16);

    rootWithChild(tracer, DROPPED_AT_TEN_PERCENT, true);

    assertThat(names()).containsExactly("child", "root");
  }

  @Test
  void shouldDropHealthChecksEvenInTheSampledFraction() {
    Tracer tracer = tracer(1, 16);

    Span health =
        tracer
            .spanBuilder("GET /actuator/health/readiness")
            .setParent(remoteParent(KEPT_AT_TEN_PERCENT))
            .setAttribute(ErrorKeepingSpanProcessor.URL_PATH, "/actuator/health/readiness")
            .startSpan();
    health.end();

    assertThat(exported.getFinishedSpanItems()).isEmpty();
  }

  @Test
  void shouldExportASpanEndingAfterItsKeptRootLikeTheRestOfTheTrace() {
    Tracer tracer = tracer(0.1, 16);
    Span root = tracer.spanBuilder("root").setParent(remoteParent(KEPT_AT_TEN_PERCENT)).startSpan();
    Span late = tracer.spanBuilder("late").setParent(Context.current().with(root)).startSpan();
    root.end();

    late.end();

    assertThat(names()).containsExactly("root", "late");
  }

  @Test
  void shouldDecideTheOldestPendingTraceOnceTooManyArePending() {
    Tracer tracer = tracer(1, 1);
    Span firstRoot =
        tracer.spanBuilder("first-root").setParent(remoteParent(KEPT_AT_TEN_PERCENT)).startSpan();
    Span secondRoot =
        tracer
            .spanBuilder("second-root")
            .setParent(remoteParent(DROPPED_AT_TEN_PERCENT))
            .startSpan();
    child(tracer, firstRoot, "first-child");

    child(tracer, secondRoot, "second-child");

    assertThat(names()).containsExactly("first-child");
  }

  @Test
  void shouldReleasePendingSampledSpansOnFlush() {
    ErrorKeepingSpanProcessor processor =
        new ErrorKeepingSpanProcessor(SimpleSpanProcessor.create(exported), 1, 16);
    Tracer tracer = SdkTracerProvider.builder().addSpanProcessor(processor).build().get("test");
    Span root = tracer.spanBuilder("root").setParent(remoteParent(KEPT_AT_TEN_PERCENT)).startSpan();
    child(tracer, root, "child");

    processor.forceFlush();

    assertThat(names()).containsExactly("child");
  }

  @Test
  void shouldRejectARatioOutsideZeroToOne() {
    assertThatThrownBy(
            () -> new ErrorKeepingSpanProcessor(SimpleSpanProcessor.create(exported), 1.5, 16))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private Tracer tracer(double ratio, int maxPending) {
    return SdkTracerProvider.builder()
        .addSpanProcessor(
            new ErrorKeepingSpanProcessor(SimpleSpanProcessor.create(exported), ratio, maxPending))
        .build()
        .get("test");
  }

  private static void rootWithChild(Tracer tracer, String traceId, boolean childFails) {
    Span root = tracer.spanBuilder("root").setParent(remoteParent(traceId)).startSpan();
    Span child = tracer.spanBuilder("child").setParent(Context.current().with(root)).startSpan();
    if (childFails) {
      child.setStatus(StatusCode.ERROR);
    }
    child.end();
    root.end();
  }

  private static void child(Tracer tracer, Span parent, String name) {
    tracer.spanBuilder(name).setParent(Context.current().with(parent)).startSpan().end();
  }

  private static Context remoteParent(String traceId) {
    SpanContext parent =
        SpanContext.createFromRemoteParent(
            traceId, "00f067aa0ba902b7", TraceFlags.getSampled(), TraceState.getDefault());
    return Context.root().with(Span.wrap(parent));
  }

  private List<String> names() {
    return exported.getFinishedSpanItems().stream().map(SpanData::getName).toList();
  }
}
