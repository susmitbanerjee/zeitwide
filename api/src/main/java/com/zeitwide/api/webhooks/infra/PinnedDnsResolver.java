package com.zeitwide.api.webhooks.infra;

import com.zeitwide.api.webhooks.domain.ResolvedWebhookTarget;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Locale;
import org.apache.hc.client5.http.DnsResolver;

/** Prevents the HTTP stack from performing another, potentially rebound, DNS lookup. */
final class PinnedDnsResolver implements DnsResolver {

  private final String host;
  private final InetAddress[] addresses;

  PinnedDnsResolver(ResolvedWebhookTarget target) {
    host = target.uri().getHost().toLowerCase(Locale.ROOT);
    addresses = target.addresses().toArray(InetAddress[]::new);
  }

  @Override
  public InetAddress[] resolve(String requestedHost) throws UnknownHostException {
    if (!host.equals(requestedHost.toLowerCase(Locale.ROOT))) {
      throw new UnknownHostException("The pinned webhook request used a different host");
    }
    return Arrays.copyOf(addresses, addresses.length);
  }

  @Override
  public String resolveCanonicalHostname(String requestedHost) throws UnknownHostException {
    if (!host.equals(requestedHost.toLowerCase(Locale.ROOT))) {
      throw new UnknownHostException("The pinned webhook request used a different host");
    }
    return requestedHost;
  }
}
