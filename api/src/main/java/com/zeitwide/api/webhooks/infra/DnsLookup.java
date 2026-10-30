package com.zeitwide.api.webhooks.infra;

import java.net.InetAddress;
import java.net.UnknownHostException;

@FunctionalInterface
interface DnsLookup {

  InetAddress[] resolve(String host) throws UnknownHostException;
}
