package com.zeitwide.api.webhooks.app;

import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OutboxFanoutService implements OutboxPreparation {

  private final OutboxPreparationRepository outbox;

  public OutboxFanoutService(OutboxPreparationRepository outbox) {
    this.outbox = outbox;
  }

  @Override
  @Transactional
  public int fanOutPending(int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Outbox fan-out limit must be between 1 and 500");
    }
    List<Long> ids = outbox.lockPendingOutbox(limit);
    if (ids.isEmpty()) {
      return 0;
    }
    outbox.createMatchingDeliveries(ids);
    outbox.markOutboxDispatched(ids);
    return ids.size();
  }
}
