package com.settlecal.api.ratelimits.api;

import java.util.Arrays;
import java.util.List;

/**
 * The client's address for anonymous limits. X-Forwarded-For is set by the client too, so only the
 * entries our own proxies appended are trusted: with N trusted proxies, the Nth from the end.
 */
final class ClientAddress {

  private ClientAddress() {}

  static String of(String remoteAddress, String forwardedFor, int trustedProxies) {
    if (trustedProxies == 0 || forwardedFor == null || forwardedFor.isBlank()) {
      return remoteAddress;
    }
    List<String> hops =
        Arrays.stream(forwardedFor.split(",")).map(String::trim).filter(h -> !h.isEmpty()).toList();
    if (hops.size() < trustedProxies) {
      return remoteAddress;
    }
    return hops.get(hops.size() - trustedProxies);
  }
}
