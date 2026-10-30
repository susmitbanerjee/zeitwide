package com.zeitwide.api.webhooks.app;

public interface OutboxPreparation {

  int fanOutPending(int limit);
}
