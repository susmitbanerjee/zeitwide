"""CHANGELOG.md from the release's own entry history.

The database records a change event each time a reviewed change is published; the release
holds the same history as entry versions (``recorded_from`` / ``recorded_to``). Comparing a
date's consecutive versions with the same classifier that wrote the events gives the same
changes, so the changelog is a pure function of the release and never reads the database.
"""

from collections import defaultdict
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import UTC, date, datetime

from zeitwide_pipeline.models import Basis, ChangeType
from zeitwide_pipeline.publish import EntryState, change_type
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.paths import answer_path, calendar_path
from zeitwide_pipeline.site.release_view import ReleaseView, long_date

_VERBS = {
    ChangeType.CLOSURE_ADDED: "closure added",
    ChangeType.CLOSURE_REMOVED: "closure removed",
    ChangeType.CLOSURE_CHANGED: "closure changed",
    ChangeType.BASIS_CHANGED: "basis changed",
}


@dataclass(frozen=True, slots=True)
class Change:
    recorded: datetime
    calendar_id: str
    entry_date: date
    change: ChangeType
    before: EntryState | None
    after: EntryState

    def describe(self) -> str:
        reason = self.after.reason
        match self.change:
            case ChangeType.CLOSURE_CHANGED if self.before is not None:
                return f"{_VERBS[self.change]}: {self.before.reason} -> {reason}"
            case ChangeType.BASIS_CHANGED if self.before is not None:
                return (
                    f"{_VERBS[self.change]}: {self.before.basis.value} -> {self.after.basis.value}"
                )
            case ChangeType.CLOSURE_REMOVED:
                return f"{_VERBS[self.change]}: open ({reason})"
            case _:
                return f"{_VERBS[self.change]}: {reason} ({self.after.basis.value})"


def changes(document: Mapping[str, object]) -> list[Change]:
    """Every change in the release, oldest first."""
    found: list[Change] = []
    calendars = document.get("calendars")
    if not isinstance(calendars, list):
        raise TypeError("A release has a list of calendars")
    for calendar in calendars:
        by_date: dict[str, list[Mapping[str, object]]] = defaultdict(list)
        for entry in calendar.get("entries", []):
            by_date[str(entry["date"])].append(entry)
        for day, versions in by_date.items():
            found += _date_changes(str(calendar["id"]), date.fromisoformat(day), versions)
    return sorted(found, key=lambda c: (c.recorded, c.calendar_id, c.entry_date))


def _date_changes(
    calendar_id: str, day: date, versions: list[Mapping[str, object]]
) -> list[Change]:
    ordered = sorted(versions, key=lambda v: datetime.fromisoformat(str(v["recorded_from"])))
    out: list[Change] = []
    before: EntryState | None = None
    for version in ordered:
        after = _state(version)
        try:
            kind = change_type(before, after)
        except ValueError:
            # Same as before, or a first version that opens a day: nothing to announce.
            kind = None
        if kind is not None:
            recorded = datetime.fromisoformat(str(version["recorded_from"])).astimezone(UTC)
            out.append(Change(recorded, calendar_id, day, kind, before, after))
        before = after
    return out


def changelog_md(document: Mapping[str, object], release: ReleaseView, links: SiteLinks) -> str:
    """Newest first, one section per day the data changed."""
    by_day: dict[date, list[Change]] = defaultdict(list)
    for change in changes(document):
        by_day[change.recorded.date()].append(change)
    lines = [
        "# Changelog",
        "",
        "Every change to the data, from the entry history in the release "
        f"{release.id}. Each line links to the calendar's page on Zeitwide and to the "
        "answer for the date. Newest first.",
    ]
    for day in sorted(by_day, reverse=True):
        lines += ["", f"## {day.isoformat()}", ""]
        for change in by_day[day]:
            calendar = f"[{change.calendar_id}]({links.page(calendar_path(change.calendar_id))})"
            date_link = (
                f"[{long_date(change.entry_date)}]"
                f"({links.page(answer_path(change.calendar_id, change.entry_date))})"
            )
            lines.append(f"- {calendar} {date_link}: {change.describe()}")
    if not by_day:
        lines += ["", "No changes recorded yet."]
    return "\n".join(lines) + "\n"


def _state(version: Mapping[str, object]) -> EntryState:
    return EntryState(
        is_open=version.get("is_open") is True,
        reason=str(version["reason"]),
        basis=Basis(str(version["basis"])),
    )
