package com.zeitwide.api.webhooks.api;

import com.zeitwide.api.webhooks.app.OutboxDrainService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/internal/outbox")
public class InternalOutboxController {

  private final OutboxDrainService drain;

  public InternalOutboxController(OutboxDrainService drain) {
    this.drain = drain;
  }

  @PostMapping("/drain")
  OutboxDrainService.DrainResult drain() {
    return drain.drain();
  }
}
