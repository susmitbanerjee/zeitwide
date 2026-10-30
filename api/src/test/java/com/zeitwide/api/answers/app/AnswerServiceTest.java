package com.zeitwide.api.answers.app;

import static com.zeitwide.api.answers.GoldenDatabase.GOLDEN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeitwide.api.answers.domain.AnswerEngine;
import com.zeitwide.api.answers.domain.Combine;
import com.zeitwide.api.answers.domain.Dataset;
import com.zeitwide.api.answers.domain.DayAnswer;
import com.zeitwide.api.answers.domain.ReleaseStamp;
import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnswerServiceTest {

  private static final List<CalendarId> FEDWIRE = List.of(new CalendarId("US-FEDWIRE"));
  private static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);
  private static final Instant NOW = Instant.parse("2026-11-01T00:00:00Z");

  private final CountingSource source = new CountingSource();
  private final AnswerService answers = service(source);

  @Test
  void shouldCacheAnAnswerByRequestAndRelease() {
    Answered<DayAnswer> first = answers.check(FEDWIRE, Combine.ALL_OPEN, THANKSGIVING, AsOf.now());
    Answered<DayAnswer> second = answers.check(FEDWIRE, Combine.ALL_OPEN, THANKSGIVING, AsOf.now());

    assertThat(second).isSameAs(first);
    assertThat(source.loads.get()).isEqualTo(1);
  }

  @Test
  void shouldNotShareAnswersBetweenDifferentRequests() {
    Answered<DayAnswer> allOpen =
        answers.check(FEDWIRE, Combine.ALL_OPEN, THANKSGIVING, AsOf.now());
    Answered<DayAnswer> past =
        answers.check(
            FEDWIRE,
            Combine.ALL_OPEN,
            THANKSGIVING,
            AsOf.at(Instant.parse("2026-10-28T12:00:00Z")));

    assertThat(past).isNotSameAs(allOpen);
    assertThat(past.asOf()).isEqualTo(Instant.parse("2026-10-28T12:00:00Z"));
  }

  @Test
  void shouldCapAsOfAtTheRelease() {
    Answered<DayAnswer> answered =
        answers.check(FEDWIRE, Combine.ALL_OPEN, THANKSGIVING, AsOf.now());

    assertThat(answered.asOf()).isEqualTo(GOLDEN.asOf());
    assertThat(answered.release()).isEqualTo(GOLDEN.release());
    assertThat(answered.revisions()).containsExactly(new CalendarRevision(FEDWIRE.getFirst(), 1));
  }

  @Test
  void shouldListCalendarsInIdOrder() {
    List<CalendarSummary> calendars = answers.calendars(AsOf.now()).answer();

    assertThat(calendars).extracting(summary -> summary.rules().id().value()).isSorted();
  }

  @Test
  void shouldReportWhenNoReleaseHasBeenCut() {
    source.release = Optional.empty();

    assertThatThrownBy(() -> answers.calendars(AsOf.now()))
        .isInstanceOf(DatasetUnavailableException.class);
  }

  private static AnswerService service(DatasetSource source) {
    AnswerProperties properties = new AnswerProperties(100, Duration.ofMinutes(1));
    CurrentDataset current = new CurrentDataset(source, new DatasetLoader(source), properties);
    return new AnswerService(
        new AnswerEngine(), current, Clock.fixed(NOW, ZoneOffset.UTC), properties);
  }

  private static final class CountingSource implements DatasetSource {
    private final AtomicInteger loads = new AtomicInteger();
    private Optional<ReleaseStamp> release =
        Optional.of(new ReleaseStamp(GOLDEN.release(), GOLDEN.asOf()));

    @Override
    public Optional<ReleaseStamp> latestRelease() {
      return release;
    }

    @Override
    public Dataset load(ReleaseStamp stamp) {
      loads.incrementAndGet();
      return GOLDEN;
    }
  }
}
