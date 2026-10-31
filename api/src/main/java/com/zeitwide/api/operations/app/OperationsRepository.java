package com.zeitwide.api.operations.app;

import com.zeitwide.api.operations.domain.ReleaseSummary;
import com.zeitwide.api.operations.domain.SourceHealth;
import java.util.List;

/** Read-only views of what the pipeline records: source checks and dataset releases. */
public interface OperationsRepository {

  List<SourceHealth> sources();

  List<ReleaseSummary> releases(int limit);
}
