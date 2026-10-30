package com.zeitwide.api.webhooks.infra;

import com.zeitwide.api.webhooks.app.UnsafeWebhookTargetException;
import com.zeitwide.api.webhooks.app.WebhookTargetGuard;
import com.zeitwide.api.webhooks.domain.ResolvedWebhookTarget;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Resolves once, rejects any non-public answer, and returns the checked addresses for pinning. */
@Component
final class PublicHttpsWebhookTargetGuard implements WebhookTargetGuard {

  private final DnsLookup dns;

  PublicHttpsWebhookTargetGuard(DnsLookup dns) {
    this.dns = dns;
  }

  @Override
  public ResolvedWebhookTarget resolveAndValidate(URI uri) {
    if (!validUri(uri)) {
      throw new UnsafeWebhookTargetException();
    }
    String host = unbracket(uri.getHost());
    try {
      InetAddress[] addresses = dns.resolve(host);
      if (addresses.length == 0
          || Arrays.stream(addresses).anyMatch(address -> !isPublic(address))) {
        throw new UnsafeWebhookTargetException();
      }
      return new ResolvedWebhookTarget(uri, List.of(addresses));
    } catch (UnknownHostException failure) {
      throw new UnsafeWebhookTargetException();
    }
  }

  private static boolean validUri(URI uri) {
    if (uri == null
        || !uri.isAbsolute()
        || uri.isOpaque()
        || uri.getScheme() == null
        || !uri.getScheme().equalsIgnoreCase("https")
        || uri.getHost() == null
        || uri.getRawUserInfo() != null
        || uri.getRawFragment() != null
        || (uri.getPort() != -1 && uri.getPort() != 443)) {
      return false;
    }
    String host = unbracket(uri.getHost()).toLowerCase(Locale.ROOT);
    if (host.endsWith(".")) {
      host = host.substring(0, host.length() - 1);
    }
    return !host.equals("localhost")
        && !host.equals("metadata")
        && !host.equals("metadata.google.internal")
        && !host.endsWith(".localhost")
        && !host.endsWith(".local")
        && !host.endsWith(".internal");
  }

  private static boolean isPublic(InetAddress address) {
    if (address.isAnyLocalAddress()
        || address.isLoopbackAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()) {
      return false;
    }
    byte[] bytes = address.getAddress();
    if (isIpv4Mapped(bytes)) {
      return isPublicIpv4(Arrays.copyOfRange(bytes, 12, 16));
    }
    if (address instanceof Inet4Address) {
      return isPublicIpv4(bytes);
    }
    if (!(address instanceof Inet6Address)) {
      return false;
    }
    // Only global-unicast 2000::/3 is accepted; reject protocol, documentation and 6to4 space.
    if ((bytes[0] & 0xe0) != 0x20) {
      return false;
    }
    boolean protocolAssignment =
        Byte.toUnsignedInt(bytes[0]) == 0x20
            && Byte.toUnsignedInt(bytes[1]) == 0x01
            && Byte.toUnsignedInt(bytes[2]) <= 0x01;
    boolean documentation =
        Byte.toUnsignedInt(bytes[0]) == 0x20
            && Byte.toUnsignedInt(bytes[1]) == 0x01
            && Byte.toUnsignedInt(bytes[2]) == 0x0d
            && Byte.toUnsignedInt(bytes[3]) == 0xb8;
    boolean sixToFour =
        Byte.toUnsignedInt(bytes[0]) == 0x20 && Byte.toUnsignedInt(bytes[1]) == 0x02;
    return !protocolAssignment && !documentation && !sixToFour;
  }

  private static boolean isPublicIpv4(byte[] bytes) {
    int first = Byte.toUnsignedInt(bytes[0]);
    int second = Byte.toUnsignedInt(bytes[1]);
    int third = Byte.toUnsignedInt(bytes[2]);
    return first != 0
        && first != 10
        && first != 127
        && first < 224
        && !(first == 100 && second >= 64 && second <= 127)
        && !(first == 169 && second == 254)
        && !(first == 172 && second >= 16 && second <= 31)
        && !(first == 192 && second == 0 && third == 0)
        && !(first == 192 && second == 0 && third == 2)
        && !(first == 192 && second == 88 && third == 99)
        && !(first == 192 && second == 168)
        && !(first == 198 && (second == 18 || second == 19))
        && !(first == 198 && second == 51 && third == 100)
        && !(first == 203 && second == 0 && third == 113);
  }

  private static boolean isIpv4Mapped(byte[] bytes) {
    if (bytes.length != 16) {
      return false;
    }
    for (int i = 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return false;
      }
    }
    return bytes[10] == (byte) 0xff && bytes[11] == (byte) 0xff;
  }

  private static String unbracket(String host) {
    if (host.startsWith("[") && host.endsWith("]")) {
      return host.substring(1, host.length() - 1);
    }
    return host;
  }
}
