package com.zeitwide.api.ratelimits.domain;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The network an anonymous caller is counted under: its IPv4 /24 or IPv6 /48. Counting by network
 * rather than address stops one host from multiplying its allowance across a block of addresses.
 */
public final class ClientNetwork {

  private static final Pattern IPV4 =
      Pattern.compile("(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.\\d{1,3}");
  private static final int MAX_OCTET = 255;
  private static final int IPV6_PREFIX_GROUPS = 3;

  private ClientNetwork() {}

  /**
   * The network, such as {@code 203.0.113.0/24} or {@code 2001:db8:1::/48}. Anything that is not an
   * IP literal (never expected behind Cloud Run) counts as a network of its own.
   */
  public static String of(String address) {
    if (address == null) {
      return "other:";
    }
    Matcher ipv4 = IPV4.matcher(address);
    if (ipv4.matches()) {
      return ipv4(address, ipv4);
    }
    // A string with a colon is only ever parsed as an IPv6 literal, never looked up in DNS.
    if (address.indexOf(':') >= 0) {
      return ipv6(address);
    }
    return "other:" + address;
  }

  private static String ipv4(String address, Matcher octets) {
    StringBuilder network = new StringBuilder();
    for (int group = 1; group <= 3; group++) {
      int octet = Integer.parseInt(octets.group(group));
      if (octet > MAX_OCTET) {
        return "other:" + address;
      }
      network.append(octet).append('.');
    }
    return network.append("0/24").toString();
  }

  private static String ipv6(String address) {
    try {
      InetAddress parsed = InetAddress.getByName(address);
      byte[] bytes = parsed.getAddress();
      if (parsed instanceof Inet4Address) {
        // An IPv4-mapped IPv6 address: count it as the IPv4 address it is.
        String mapped = (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff);
        return mapped + ".0/24";
      }
      StringBuilder network = new StringBuilder();
      for (int group = 0; group < IPV6_PREFIX_GROUPS; group++) {
        int value = ((bytes[2 * group] & 0xff) << 8) | (bytes[2 * group + 1] & 0xff);
        network.append(Integer.toHexString(value)).append(':');
      }
      return network.append(":/48").toString();
    } catch (UnknownHostException invalidLiteral) {
      return "other:" + address;
    }
  }
}
