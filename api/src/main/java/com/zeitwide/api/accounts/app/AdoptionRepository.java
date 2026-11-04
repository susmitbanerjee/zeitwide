package com.zeitwide.api.accounts.app;

import com.zeitwide.api.accounts.domain.AdoptionCounts;
import java.time.Instant;

public interface AdoptionRepository {

  /** Active keys are those not revoked; used keys also called the API since {@code usedSince}. */
  AdoptionCounts counts(Instant usedSince);
}
