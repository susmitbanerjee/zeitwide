package com.zeitwide.api.webhooks.app;

import java.util.List;

public interface OutboxPreparationRepository {

  List<Long> lockPendingOutbox(int limit);

  void createMatchingDeliveries(List<Long> outboxIds);

  void markOutboxDispatched(List<Long> outboxIds);
}
