package com.zeitwide.api.answers.infra;

import static com.zeitwide.api.answers.GoldenDatabase.GOLDEN;
import static org.assertj.core.api.Assertions.assertThat;

import com.zeitwide.api.PostgresJdbcTest;
import com.zeitwide.api.answers.GoldenDatabase;
import com.zeitwide.api.answers.domain.ConformanceRunner;
import com.zeitwide.api.answers.domain.Dataset;
import com.zeitwide.api.answers.domain.EntryVersion;
import com.zeitwide.api.answers.domain.ReleaseStamp;
import com.zeitwide.api.answers.domain.SourceRef;
import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@PostgresJdbcTest
@Import(JdbcDatasetSource.class)
class JdbcDatasetSourceTest {

  private static final CalendarId FEDWIRE = new CalendarId("US-FEDWIRE");
  private static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);

  @Autowired private JdbcDatasetSource source;
  @Autowired private JdbcClient jdbc;

  @BeforeEach
  void loadGoldenDataset() {
    GoldenDatabase.load(jdbc);
  }

  @Test
  void shouldFindTheLatestRelease() {
    assertThat(source.latestRelease()).contains(new ReleaseStamp(GOLDEN.release(), GOLDEN.asOf()));
  }

  @Test
  void shouldLoadADatasetThatPassesEveryConformanceCase() {
    Dataset loaded = source.load(new ReleaseStamp(GOLDEN.release(), GOLDEN.asOf()));
    ConformanceRunner runner = new ConformanceRunner();

    List<ConformanceRunner.CaseResult> failed =
        ConformanceRunner.casePaths().stream()
            .map(path -> runner.run(loaded, ConformanceRunner.readCase(path)))
            .filter(result -> !result.passed())
            .toList();

    assertThat(failed).isEmpty();
  }

  @Test
  void shouldLoadEveryVersionWithItsEvidence() {
    Dataset loaded = source.load(new ReleaseStamp(GOLDEN.release(), GOLDEN.asOf()));

    for (CalendarId id : GOLDEN.calendars().keySet()) {
      assertThat(loaded.calendar(id).versions().stream().map(JdbcDatasetSourceTest::essence))
          .as(id.value())
          .containsExactlyElementsOf(
              GOLDEN.calendar(id).versions().stream().map(JdbcDatasetSourceTest::essence).toList());
      assertThat(loaded.calendar(id).rules()).isEqualTo(GOLDEN.calendar(id).rules());
    }
  }

  // The database keeps one URL per source, not per snapshot, so URLs are left out here.
  private static List<Object> essence(EntryVersion version) {
    return List.of(
        version.date(),
        version.open(),
        version.reason(),
        version.basis(),
        version.revision(),
        version.recordedFrom(),
        version.recordedTo(),
        version.sources().stream().map(SourceRef::sha256).sorted().toList(),
        version.sources().stream().map(SourceRef::publisher).sorted().toList());
  }

  @Test
  void shouldTreatAVersionReplacedAfterTheReleaseAsCurrent() {
    jdbc.sql(
            """
            SELECT publish_entry_version('US-FEDWIRE', DATE '2026-11-26', true, 'Open after all',
                                         'published', 99, :later)
            """)
        .param("later", GOLDEN.asOf().plusSeconds(60).atOffset(ZoneOffset.UTC))
        .query()
        .singleRow();

    Dataset loaded = source.load(new ReleaseStamp(GOLDEN.release(), GOLDEN.asOf()));

    assertThat(loaded.calendar(FEDWIRE).versions())
        .filteredOn(version -> version.date().equals(THANKSGIVING))
        .singleElement()
        .satisfies(
            version -> {
              assertThat(version.isCurrent()).isTrue();
              assertThat(version.open()).isFalse();
            })
        .extracting(EntryVersion::reason)
        .isEqualTo("Thanksgiving Day");
  }
}
