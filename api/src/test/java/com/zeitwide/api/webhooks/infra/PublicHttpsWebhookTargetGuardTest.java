package com.zeitwide.api.webhooks.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeitwide.api.webhooks.app.UnsafeWebhookTargetException;
import java.net.InetAddress;
import java.net.URI;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PublicHttpsWebhookTargetGuardTest {

  @Test
  void allowsPublicHttpsAndRejectsPrivateLoopbackLinkLocalAndMetadataAddresses() throws Exception {
    assertThat(
            new PublicHttpsWebhookTargetGuard(host -> addresses("8.8.8.8"))
                .resolveAndValidate(URI.create("https://hooks.example.com/events"))
                .addresses())
        .containsExactly(InetAddress.getByName("8.8.8.8"));

    for (String address :
        new String[] {"10.0.0.1", "127.0.0.1", "169.254.169.254", "::1", "fc00::1"}) {
      var guard = new PublicHttpsWebhookTargetGuard(host -> addresses(address));
      assertThatThrownBy(() -> guard.resolveAndValidate(URI.create("https://hooks.example.com")))
          .isInstanceOf(UnsafeWebhookTargetException.class);
    }

    var metadataGuard = new PublicHttpsWebhookTargetGuard(host -> addresses("8.8.8.8"));
    assertThatThrownBy(
            () ->
                metadataGuard.resolveAndValidate(
                    URI.create("https://metadata.google.internal/computeMetadata/v1")))
        .isInstanceOf(UnsafeWebhookTargetException.class);
  }

  @Test
  void rejectsInsecureAndNonstandardTargetUrlsBeforeDnsResolution() {
    AtomicInteger lookups = new AtomicInteger();
    var guard =
        new PublicHttpsWebhookTargetGuard(
            host -> {
              lookups.incrementAndGet();
              return addresses("8.8.8.8");
            });

    for (String url :
        new String[] {
          "http://hooks.example.com/events",
          "https://user:password@hooks.example.com/events",
          "https://hooks.example.com:8443/events",
          "https://localhost/events"
        }) {
      assertThatThrownBy(() -> guard.resolveAndValidate(URI.create(url)))
          .isInstanceOf(UnsafeWebhookTargetException.class);
    }
    assertThat(lookups).hasValue(0);
  }

  @Test
  void pinsTheFirstPublicDnsAnswerAndRejectsPrivateRebindingOnTheNextRequest() throws Exception {
    InetAddress publicIp = InetAddress.getByName("8.8.8.8");
    InetAddress privateIp = InetAddress.getByName("169.254.169.254");
    AtomicInteger lookups = new AtomicInteger();
    var guard =
        new PublicHttpsWebhookTargetGuard(
            host -> {
              if (lookups.getAndIncrement() == 0) {
                return new InetAddress[] {publicIp};
              }
              return new InetAddress[] {privateIp};
            });
    URI uri = URI.create("https://hooks.example.com/events");
    var checkedTarget = guard.resolveAndValidate(uri);

    var pinnedResolver = new PinnedDnsResolver(checkedTarget);
    assertThat(pinnedResolver.resolve(uri.getHost())).containsExactly(publicIp);
    assertThat(lookups).hasValue(1);
    assertThatThrownBy(() -> guard.resolveAndValidate(uri))
        .isInstanceOf(UnsafeWebhookTargetException.class);
    assertThat(lookups).hasValue(2);
  }

  private static InetAddress[] addresses(String address) throws java.net.UnknownHostException {
    return new InetAddress[] {InetAddress.getByName(address)};
  }
}
