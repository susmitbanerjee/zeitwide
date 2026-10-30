package com.zeitwide.api.calendars.infra;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zeitwide.api.PostgresJdbcTest;
import com.zeitwide.api.calendars.domain.Basis;
import com.zeitwide.api.calendars.domain.CalendarEntry;
import com.zeitwide.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@PostgresJdbcTest
@Import(JdbcCalendarEntryRepository.class)
class JdbcCalendarEntryRepositoryTest {

  private static final CalendarId FEDWIRE = new CalendarId("US-FEDWIRE");
  private static final LocalDate THANKSGIVING = LocalDate.of(2026, 11, 26);
  private static final Instant JAN_1 = Instant.parse("2026-01-01T00:00:00Z");
  private static final Instant JAN_15 = Instant.parse("2026-01-15T00:00:00Z");
  private static final Instant FEB_1 = Instant.parse("2026-02-01T00:00:00Z");
  private static final Instant MAR_1 = Instant.parse("2026-03-01T00:00:00Z");

  @Autowired private JdbcCalendarEntryRepository repository;
  @Autowired private JdbcClient jdbc;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private PlatformTransactionManager transactionManager;

  @BeforeEach
  void createCalendar() {
    jdbc.sql(
            """
            INSERT INTO calendar (id, name, kind, timezone, weekend_rule)
            VALUES (:id, 'Fedwire Funds Service', 'rail', 'America/New_York', '{}')
            """)
        .param("id", FEDWIRE.value())
        .update();
  }

  @Test
  void shouldReturnVersionRecordedAtRequestedTime() {
    publish(THANKSGIVING, false, "Thanksgiving", "projected", 1, JAN_1);
    publish(THANKSGIVING, false, "Thanksgiving Day", "published", 2, FEB_1);

    assertThat(repository.findAsOf(FEDWIRE, THANKSGIVING, JAN_15))
        .get()
        .extracting(CalendarEntry::reason, CalendarEntry::basis, CalendarEntry::recordedFrom)
        .containsExactly("Thanksgiving", Basis.PROJECTED, JAN_1);
  }

  @Test
  void shouldReturnNewVersionFromTheInstantItWasRecorded() {
    publish(THANKSGIVING, false, "Thanksgiving", "projected", 1, JAN_1);
    publish(THANKSGIVING, false, "Thanksgiving Day", "published", 2, FEB_1);

    assertThat(repository.findAsOf(FEDWIRE, THANKSGIVING, FEB_1))
        .get()
        .extracting(CalendarEntry::revision)
        .isEqualTo(2L);
  }

  @Test
  void shouldReturnNothingBeforeTheFirstVersionWasRecorded() {
    publish(THANKSGIVING, false, "Thanksgiving", "published", 1, FEB_1);

    assertThat(repository.findAsOf(FEDWIRE, THANKSGIVING, JAN_15)).isEmpty();
  }

  @Test
  void shouldCloseCurrentVersionWhenPublishingNewOne() {
    publish(THANKSGIVING, false, "Thanksgiving", "projected", 1, JAN_1);
    publish(THANKSGIVING, false, "Thanksgiving Day", "published", 2, FEB_1);

    assertThat(
            jdbc.sql(
                    """
                    SELECT upper(recorded_during) FROM calendar_entry
                    WHERE calendar_id = :id AND revision = 1
                    """)
                .param("id", FEDWIRE.value())
                .query(OffsetDateTime.class)
                .single()
                .toInstant())
        .isEqualTo(FEB_1);
  }

  @Test
  void shouldRejectOverlappingVersion() {
    publish(THANKSGIVING, false, "Thanksgiving", "published", 1, FEB_1);

    assertThatThrownBy(
            () ->
                inSavepoint(
                    """
                    INSERT INTO calendar_entry
                        (calendar_id, entry_date, is_open, reason, basis, revision, recorded_during)
                    VALUES ('US-FEDWIRE', DATE '2026-11-26', true, 'Open', 'published', 2,
                            tstzrange('2026-01-01Z', '2026-03-01Z', '[)'))
                    """))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("calendar_entry_no_overlap");
  }

  @Test
  void shouldKeepCurrentVersionWhenNewVersionIsInvalid() {
    publish(THANKSGIVING, false, "Thanksgiving", "published", 1, JAN_1);

    assertThatThrownBy(
            () ->
                inSavepoint(
                    """
                    SELECT publish_entry_version('US-FEDWIRE', DATE '2026-11-26', true, 'Open',
                                                 'guessed', 2, TIMESTAMPTZ '2026-02-01Z')
                    """))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertCurrentVersionIsStill(1);
  }

  @Test
  void shouldKeepCurrentVersionWhenRecordedTimeIsNotLater() {
    publish(THANKSGIVING, false, "Thanksgiving", "published", 1, FEB_1);

    assertThatThrownBy(
            () ->
                inSavepoint(
                    """
                    SELECT publish_entry_version('US-FEDWIRE', DATE '2026-11-26', true, 'Open',
                                                 'published', 2, TIMESTAMPTZ '2026-01-01Z')
                    """))
        .isInstanceOf(DataAccessException.class);
    assertCurrentVersionIsStill(1);
  }

  @Test
  void shouldRejectUpdatingAnEntryInPlace() {
    publish(THANKSGIVING, false, "Thanksgiving", "published", 1, JAN_1);

    assertThatThrownBy(() -> inSavepoint("UPDATE calendar_entry SET reason = 'Edited'"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void shouldRejectDeletingAnEntry() {
    publish(THANKSGIVING, false, "Thanksgiving", "published", 1, JAN_1);

    assertThatThrownBy(() -> inSavepoint("DELETE FROM calendar_entry"))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void shouldListOnlyCurrentClosuresInDateOrder() {
    LocalDate christmasEve = LocalDate.of(2026, 12, 24);
    LocalDate christmas = LocalDate.of(2026, 12, 25);
    publish(christmas, false, "Christmas Day", "published", 1, JAN_1);
    publish(THANKSGIVING, false, "Thanksgiving", "published", 1, JAN_1);
    publish(LocalDate.of(2026, 11, 27), true, "Open after Thanksgiving", "published", 1, JAN_1);
    publish(christmasEve, false, "Christmas Eve", "projected", 1, JAN_1);
    publish(christmasEve, true, "Christmas Eve is a business day", "published", 2, FEB_1);

    assertThat(
            repository.findCurrentClosures(
                FEDWIRE, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 12, 31)))
        .extracting(CalendarEntry::entryDate)
        .containsExactly(THANKSGIVING, christmas);
  }

  @Test
  void shouldRejectClosureRangeEndingBeforeItStarts() {
    assertThatThrownBy(
            () -> repository.findCurrentClosures(FEDWIRE, THANKSGIVING, THANKSGIVING.minusDays(1)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private void publish(
      LocalDate date, boolean open, String reason, String basis, long revision, Instant at) {
    jdbc.sql(
            """
            SELECT id FROM publish_entry_version(
                :calendarId, :date, :open, :reason, :basis, :revision, :at)
            """)
        .param("calendarId", FEDWIRE.value())
        .param("date", date)
        .param("open", open)
        .param("reason", reason)
        .param("basis", basis)
        .param("revision", revision)
        .param("at", at.atOffset(ZoneOffset.UTC))
        .query(Long.class)
        .single();
  }

  /** Runs SQL that is expected to fail, rolling back only to a savepoint. */
  private void inSavepoint(String sql) {
    TransactionTemplate nested = new TransactionTemplate(transactionManager);
    nested.setPropagationBehavior(TransactionDefinition.PROPAGATION_NESTED);
    nested.executeWithoutResult(status -> jdbcTemplate.execute(sql));
  }

  private void assertCurrentVersionIsStill(long revision) {
    assertThat(repository.findAsOf(FEDWIRE, THANKSGIVING, MAR_1))
        .get()
        .extracting(CalendarEntry::revision)
        .isEqualTo(revision);
    assertThat(
            jdbc.sql("SELECT count(*) FROM calendar_entry WHERE calendar_id = :id")
                .param("id", FEDWIRE.value())
                .query(Long.class)
                .single())
        .isEqualTo(1L);
  }
}
