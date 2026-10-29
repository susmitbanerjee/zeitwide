package com.settlecal.api.answers.app;

import com.settlecal.api.answers.domain.Dataset;
import com.settlecal.api.answers.domain.ReleaseStamp;
import java.util.Optional;

/** Where the dataset comes from: the releases and entries the pipeline wrote to Postgres. */
public interface DatasetSource {

  /** The release cut most recently, if any has been. */
  Optional<ReleaseStamp> latestRelease();

  /** Every calendar and every entry version recorded up to the release's {@code asOf}. */
  Dataset load(ReleaseStamp release);
}
