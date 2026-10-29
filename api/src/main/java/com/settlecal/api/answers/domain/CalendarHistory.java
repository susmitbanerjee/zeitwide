package com.settlecal.api.answers.domain;

import com.settlecal.api.calendars.domain.CalendarId;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.IntSummaryStatistics;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** A calendar's rules and every recorded version of its entries. Read-only. */
public final class CalendarHistory {

  private final CalendarRules rules;
  private final SortedMap<LocalDate, List<EntryVersion>> versionsByDate;

  public CalendarHistory(CalendarRules rules, List<EntryVersion> versions) {
    this.rules = Objects.requireNonNull(rules, "rules");
    this.versionsByDate =
        versions.stream()
            .collect(
                Collectors.groupingBy(
                    EntryVersion::date,
                    TreeMap::new,
                    Collectors.collectingAndThen(
                        Collectors.toList(), CalendarHistory::inRecordedOrder)));
  }

  public CalendarId id() {
    return rules.id();
  }

  public CalendarRules rules() {
    return rules;
  }

  /** Every version of every entry, in date order and then in the order recorded. */
  public List<EntryVersion> versions() {
    return versionsByDate.values().stream().flatMap(List::stream).toList();
  }

  /** The version of {@code date}'s entry recorded at {@code moment}, if there was one. */
  public Optional<EntryVersion> entryAt(LocalDate date, Instant moment) {
    return versionsByDate.getOrDefault(date, List.of()).stream()
        .filter(version -> version.recordedAt(moment))
        .findFirst();
  }

  /** The days the weekend rule closes when no entry says otherwise. */
  public boolean isWeekend(LocalDate date) {
    return rules.weekend().contains(date.getDayOfWeek());
  }

  /** Closures recorded at {@code moment} from {@code from} to {@code to}, in date order. */
  public List<EntryVersion> closuresAt(LocalDate from, LocalDate to, Instant moment) {
    return versionsByDate.subMap(from, to.plusDays(1)).values().stream()
        .flatMap(List::stream)
        .filter(version -> version.recordedAt(moment) && !version.open())
        .toList();
  }

  /** The calendar's revision at {@code moment}: the latest one published by then, or 0. */
  public long revisionAt(Instant moment) {
    return versionsByDate.values().stream()
        .flatMap(List::stream)
        .filter(version -> !moment.isBefore(version.recordedFrom()))
        .mapToLong(EntryVersion::revision)
        .max()
        .orElse(0);
  }

  /** What this calendar can answer for at {@code moment} (see dataset/README.md). */
  public Coverage coverageAt(Instant moment) {
    if (!rules.closesOnHolidays()) {
      return new Coverage.Unlimited();
    }
    IntSummaryStatistics years =
        versionsByDate.values().stream()
            .flatMap(List::stream)
            .filter(version -> version.recordedAt(moment))
            .mapToInt(version -> version.date().getYear())
            .summaryStatistics();
    if (years.getCount() == 0) {
      return new Coverage.Nothing();
    }
    return new Coverage.Years(years.getMin(), years.getMax());
  }

  // Overlapping versions would make an as-of answer depend on list order.
  private static List<EntryVersion> inRecordedOrder(List<EntryVersion> versions) {
    List<EntryVersion> sorted =
        versions.stream().sorted(Comparator.comparing(EntryVersion::recordedFrom)).toList();
    for (int i = 1; i < sorted.size(); i++) {
      if (sorted.get(i).recordedFrom().isBefore(sorted.get(i - 1).recordedTo())) {
        throw new IllegalArgumentException("Versions of one date must not overlap");
      }
    }
    return sorted;
  }
}
