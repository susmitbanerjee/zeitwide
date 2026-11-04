package com.zeitwide.otel;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.ReadableSpan;
import io.opentelemetry.sdk.trace.SpanProcessor;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Keeps a fixed fraction of traces plus every trace with an error, and drops health checks.
 *
 * <p>A head sampler decides before anything has failed, so every span is recorded and this
 * processor decides once the trace's local root ends: it holds a trace's spans until then, and
 * passes them on to the exporting processor only if the trace is kept. Spans that end after the
 * decision follow it.
 */
public final class ErrorKeepingSpanProcessor implements SpanProcessor {

  static final AttributeKey<String> URL_PATH = AttributeKey.stringKey("url.path");
  private static final String HEALTH_PATH_PREFIX = "/actuator/health";

  private final SpanProcessor exporter;
  private final double ratio;
  private final int maxPendingTraces;
  // Insertion-ordered, so the oldest pending trace is the first to be forced out.
  private final Map<String, List<ReadableSpan>> pending = new LinkedHashMap<>();
  private final Map<String, Boolean> decided;

  public ErrorKeepingSpanProcessor(SpanProcessor exporter, double ratio, int maxPendingTraces) {
    if (ratio < 0 || ratio > 1) {
      throw new IllegalArgumentException("The sample ratio is between 0 and 1");
    }
    this.exporter = exporter;
    this.ratio = ratio;
    this.maxPendingTraces = maxPendingTraces;
    this.decided = new BoundedMap<>(maxPendingTraces);
  }

  @Override
  public void onStart(Context parentContext, ReadWriteSpan span) {
    exporter.onStart(parentContext, span);
  }

  @Override
  public boolean isStartRequired() {
    return exporter.isStartRequired();
  }

  @Override
  public void onEnd(ReadableSpan span) {
    List<ReadableSpan> release = List.of();
    synchronized (this) {
      String traceId = span.getSpanContext().getTraceId();
      Boolean keep = decided.get(traceId);
      if (keep != null) {
        release = keep ? List.of(span) : List.of();
      } else {
        List<ReadableSpan> spans = pending.computeIfAbsent(traceId, id -> new ArrayList<>());
        spans.add(span);
        if (isLocalRoot(span)) {
          release = decide(traceId, !isHealthCheck(span));
        } else if (pending.size() > maxPendingTraces) {
          release = decide(pending.keySet().iterator().next(), true);
        }
      }
    }
    release.forEach(exporter::onEnd);
  }

  @Override
  public boolean isEndRequired() {
    return true;
  }

  @Override
  public CompletableResultCode forceFlush() {
    releaseAll();
    return exporter.forceFlush();
  }

  @Override
  public CompletableResultCode shutdown() {
    releaseAll();
    return exporter.shutdown();
  }

  private void releaseAll() {
    List<ReadableSpan> release = new ArrayList<>();
    synchronized (this) {
      for (String traceId : List.copyOf(pending.keySet())) {
        release.addAll(decide(traceId, true));
      }
    }
    release.forEach(exporter::onEnd);
  }

  /** Removes the trace from pending and returns its spans if it is kept. */
  private List<ReadableSpan> decide(String traceId, boolean sampleable) {
    List<ReadableSpan> spans = pending.remove(traceId);
    boolean failed = spans.stream().anyMatch(ErrorKeepingSpanProcessor::failed);
    boolean keep = failed || (sampleable && TraceIdRatio.isSampled(traceId, ratio));
    decided.put(traceId, keep);
    return keep ? spans : List.of();
  }

  private static boolean isLocalRoot(ReadableSpan span) {
    SpanContext parent = span.getParentSpanContext();
    return !parent.isValid() || parent.isRemote();
  }

  private static boolean isHealthCheck(ReadableSpan span) {
    String path = span.getAttribute(URL_PATH);
    return path != null && path.startsWith(HEALTH_PATH_PREFIX);
  }

  private static boolean failed(ReadableSpan span) {
    return span.toSpanData().getStatus().getStatusCode() == StatusCode.ERROR;
  }

  private static final class BoundedMap<K, V> extends LinkedHashMap<K, V> {
    private static final long serialVersionUID = 1L;
    private final int capacity;

    BoundedMap(int capacity) {
      this.capacity = capacity;
    }

    @Override
    protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
      return size() > capacity;
    }
  }
}
