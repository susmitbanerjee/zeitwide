package com.zeitwide.api.auth.app;

import com.zeitwide.api.auth.domain.ApiKeyToken;
import java.math.BigInteger;
import java.security.SecureRandom;

/** Creates the fixed 32-byte secret and eight-character base62 lookup prefix. */
public final class ApiKeyGenerator {

  private static final String ALPHABET =
      "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
  private static final int SECRET_BYTES = 32;
  private static final int SECRET_LENGTH = 43;
  private static final BigInteger RADIX = BigInteger.valueOf(ALPHABET.length());

  private final SecureRandom random;

  public ApiKeyGenerator(SecureRandom random) {
    this.random = random;
  }

  public ApiKeyGenerator() {
    this(new SecureRandom());
  }

  public ApiKeyToken generate() {
    return new ApiKeyToken(ApiKeyToken.LIVE + randomBase62(8), randomBase62Secret());
  }

  private String randomBase62(int length) {
    StringBuilder result = new StringBuilder(length);
    while (result.length() < length) {
      byte[] sample = new byte[1];
      random.nextBytes(sample);
      int value = Byte.toUnsignedInt(sample[0]);
      if (value < 248) {
        result.append(ALPHABET.charAt(value % ALPHABET.length()));
      }
    }
    return result.toString();
  }

  private String randomBase62Secret() {
    byte[] bytes = new byte[SECRET_BYTES];
    random.nextBytes(bytes);
    BigInteger value = new BigInteger(1, bytes);
    StringBuilder reversed = new StringBuilder(SECRET_LENGTH);
    while (value.signum() > 0) {
      BigInteger[] divided = value.divideAndRemainder(RADIX);
      reversed.append(ALPHABET.charAt(divided[1].intValue()));
      value = divided[0];
    }
    while (reversed.length() < SECRET_LENGTH) {
      reversed.append('0');
    }
    return reversed.reverse().toString();
  }
}
