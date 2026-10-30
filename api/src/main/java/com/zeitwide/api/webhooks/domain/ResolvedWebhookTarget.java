package com.zeitwide.api.webhooks.domain;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;
import java.util.Objects;

/** An HTTPS URI paired with the exact DNS answers checked for this request. */
public record ResolvedWebhookTarget(URI uri, List<InetAddress> addresses) {

  public ResolvedWebhookTarget {
    Objects.requireNonNull(uri, "uri");
    addresses = List.copyOf(addresses);
    if (addresses.isEmpty()) {
      throw new IllegalArgumentException("A webhook target must have a resolved address");
    }
  }
}
