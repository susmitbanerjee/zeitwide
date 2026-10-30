package com.zeitwide.api.answers.app;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.LoadingCache;
import com.zeitwide.api.answers.domain.Dataset;
import com.zeitwide.api.answers.domain.DatasetRelease;
import com.zeitwide.api.answers.domain.ReleaseStamp;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * The dataset of the latest release, held in memory. A release never changes once cut, so it is
 * loaded once; the database is asked only now and then whether a newer release exists.
 */
@Component
class CurrentDataset {

  // One key: the latest release is a single value that expires.
  private static final String LATEST = "latest";
  // The current release, plus the one before it while requests still use it.
  private static final int RELEASES_KEPT = 2;

  private final LoadingCache<String, Optional<ReleaseStamp>> latest;
  private final Cache<DatasetRelease, Dataset> datasets;
  private final DatasetLoader loader;

  CurrentDataset(DatasetSource source, DatasetLoader loader, AnswerProperties properties) {
    this.loader = loader;
    this.latest =
        Caffeine.newBuilder()
            .expireAfterWrite(properties.releaseCheckInterval())
            .build(key -> source.latestRelease());
    this.datasets = Caffeine.newBuilder().maximumSize(RELEASES_KEPT).build();
  }

  /**
   * The latest release's dataset.
   *
   * @throws DatasetUnavailableException if no release has been cut
   */
  Dataset get() {
    ReleaseStamp stamp = latest.get(LATEST).orElseThrow(DatasetUnavailableException::new);
    return datasets.get(stamp.release(), release -> loader.load(stamp));
  }
}
