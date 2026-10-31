package com.zeitwide.api.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An API key as a caller presents it: {@code zw_live_AbCd1234_<secret>}. The prefix finds the
 * stored key; only the SHA-256 of the secret is ever stored.
 */
public record ApiKeyToken(String prefix, String secret) {

  /** Every API key starts with this; a bearer token without it is not an API key. */
  public static final String SCHEME = "zw_";

  /** The start of every key issued today. */
  public static final String LIVE = SCHEME + "live_";

  private static final Pattern FORMAT =
      Pattern.compile("(" + LIVE + "[A-Za-z0-9]{8})_([A-Za-z0-9]{43})");

  public ApiKeyToken {
    Objects.requireNonNull(prefix, "prefix");
    Objects.requireNonNull(secret, "secret");
  }

  /** The key in {@code text}, if it has the right format. Never logs or echoes the text. */
  public static Optional<ApiKeyToken> parse(String text) {
    if (text == null) {
      return Optional.empty();
    }
    Matcher matcher = FORMAT.matcher(text);
    if (!matcher.matches()) {
      return Optional.empty();
    }
    return Optional.of(new ApiKeyToken(matcher.group(1), matcher.group(2)));
  }

  public byte[] secretSha256() {
    return sha256(secret);
  }

  /** The one-time value returned by key creation; callers must never persist or log it. */
  public String value() {
    return prefix + "_" + secret;
  }

  /** Never the secret: records print every component by default. */
  @Override
  public String toString() {
    return "ApiKeyToken[prefix=" + prefix + ", secret=<redacted>]";
  }

  public static byte[] sha256(String text) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("Every JVM ships SHA-256", e);
    }
  }
}
