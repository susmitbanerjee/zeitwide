"""The public URL scheme (permanent), as the web app routes it."""

from datetime import date
from itertools import combinations

from zeitwide_pipeline.site.release_view import CalendarView, ReleaseView


def calendar_path(calendar_id: str) -> str:
    return f"/calendars/{calendar_id}"


def answer_path(calendar_id: str, day: date) -> str:
    return f"/calendars/{calendar_id}/{day.isoformat()}"


def year_path(calendar_id: str, year: int) -> str:
    return f"/calendars/{calendar_id}/{year}"


def compare_path(id_a: str, id_b: str, year: int) -> str:
    return f"/compare/{id_a}-vs-{id_b}/{year}"


def compare_pairs(release: ReleaseView, year: int) -> list[tuple[CalendarView, CalendarView]]:
    """Every pair, in sorted-ID order, that both cover ``year``: the compare pages that exist."""
    ordered = sorted(release.calendars, key=lambda calendar: calendar.id)
    return [
        (a, b)
        for a, b in combinations(ordered, 2)
        if year in a.covered_years([year]) and year in b.covered_years([year])
    ]
