package com.zeitwide.api.webhooks.infra;

import java.net.InetAddress;
import java.net.UnknownHostException;
import org.springframework.stereotype.Component;

@Component
final class SystemDnsLookup implements DnsLookup {

  @Override
  public InetAddress[] resolve(String host) throws UnknownHostException {
    return InetAddress.getAllByName(host);
  }
}
