package com.zeitwide.api.operations.app;

import com.zeitwide.api.auth.app.Entitlements;
import com.zeitwide.api.operations.domain.ReleaseSummary;
import com.zeitwide.api.operations.domain.SourceHealth;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Source health and releases for the admin screens. */
@Service
public class OperationsService {

  private final OperationsRepository operations;
  private final Entitlements entitlements;

  public OperationsService(OperationsRepository operations, Entitlements entitlements) {
    this.operations = operations;
    this.entitlements = entitlements;
  }

  @Transactional(readOnly = true)
  public List<SourceHealth> sources() {
    entitlements.check(Entitlements.Feature.ADMIN);
    return operations.sources();
  }

  @Transactional(readOnly = true)
  public List<ReleaseSummary> releases(int limit) {
    entitlements.check(Entitlements.Feature.ADMIN);
    return operations.releases(limit);
  }
}
