package com.zeitwide.otel;

/**
 * Whether a trace is in the sampled fraction, decided from its ID alone.
 *
 * <p>The web app, the API and the pipeline use this same rule, so they keep the same traces and a
 * kept trace stays whole across services. It reads the top 53 bits of the ID's random lower half as
 * a fraction in [0, 1).
 */
public final class TraceIdRatio {

  private static final int TRACE_ID_HEX_LENGTH = 32;
  private static final int LOWER_HALF_START = 16;
  private static final int DROPPED_LOW_BITS = 11;
  private static final double TWO_TO_THE_53 = 0x1p53;

  private TraceIdRatio() {}

  public static boolean isSampled(String traceId, double ratio) {
    if (traceId.length() != TRACE_ID_HEX_LENGTH) {
      throw new IllegalArgumentException("A trace ID is 32 hex characters");
    }
    long lowerHalf = Long.parseUnsignedLong(traceId.substring(LOWER_HALF_START), 16);
    return (lowerHalf >>> DROPPED_LOW_BITS) / TWO_TO_THE_53 < ratio;
  }
}
