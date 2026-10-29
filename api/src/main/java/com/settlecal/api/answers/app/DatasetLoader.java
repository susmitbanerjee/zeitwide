package com.settlecal.api.answers.app;

import com.settlecal.api.answers.domain.Dataset;
import com.settlecal.api.answers.domain.ReleaseStamp;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Loads a release's dataset in one read-only transaction, so its queries see one snapshot. */
@Component
class DatasetLoader {

  private final DatasetSource source;

  DatasetLoader(DatasetSource source) {
    this.source = source;
  }

  @Transactional(readOnly = true)
  public Dataset load(ReleaseStamp release) {
    return source.load(release);
  }
}
