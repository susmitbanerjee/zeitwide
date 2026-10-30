package com.zeitwide.api.webhooks.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class WebhookSignatureTest {

  @Test
  void signsTheTimestampAndExactBodyBytesWithHmacSha256() {
    String signature =
        WebhookSignature.header(
            "whsec_test", 1_700_000_000L, "payload".getBytes(StandardCharsets.UTF_8));

    assertThat(signature)
        .isEqualTo(
            "t=1700000000,v1=f572f0b95edf70be10ba738b573f69161af6408b9b373f71801c8ed68a729b1e");
  }
}
