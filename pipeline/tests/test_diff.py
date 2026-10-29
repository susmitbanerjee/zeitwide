from datetime import date

import pytest

from settlecal_pipeline.diff import (
    NOT_LISTED_REASON,
    PendingCandidate,
    PublishedEntry,
    corroboration,
    plan_diff,
)
from settlecal_pipeline.models import Basis, Corroboration, Observation

THANKSGIVING = date(2026, 11, 26)
KEY = ("US-FEDWIRE", THANKSGIVING)
COLUMBUS_DAY = date(2026, 10, 12)
COLUMBUS_KEY = ("US-FEDWIRE", COLUMBUS_DAY)
PUBLISHED_COLUMBUS_DAY = {COLUMBUS_KEY: PublishedEntry(False, "Columbus Day", Basis.PUBLISHED)}


def closed(reason: str = "Thanksgiving Day") -> Observation:
    return Observation("US-FEDWIRE", THANKSGIVING, False, reason)


def opened() -> Observation:
    return Observation("US-FEDWIRE", THANKSGIVING, True, "Open")


def test_new_closure_opens_a_candidate() -> None:
    plan = plan_diff([closed()], {}, {})

    assert [(c.calendar_id, c.entry_date, c.before, c.after) for c in plan.new_candidates] == [
        ("US-FEDWIRE", THANKSGIVING, None, closed())
    ]
    assert plan.supporting == (closed(),)


def test_closure_matching_published_entry_is_no_change() -> None:
    published = {KEY: PublishedEntry(False, "Thanksgiving", Basis.PUBLISHED)}

    plan = plan_diff([closed("Thanksgiving Day")], published, {})

    assert plan.new_candidates == ()
    assert plan.supporting == ()


def test_open_day_without_entry_is_no_change() -> None:
    assert plan_diff([opened()], {}, {}).new_candidates == ()


def test_reopening_a_published_closure_opens_a_candidate() -> None:
    before = PublishedEntry(False, "Thanksgiving", Basis.PROJECTED)

    plan = plan_diff([opened()], {KEY: before}, {})

    assert [candidate.before for candidate in plan.new_candidates] == [before]


def test_agreeing_observation_supports_pending_candidate() -> None:
    plan = plan_diff([closed()], {}, {KEY: PendingCandidate(id=7, is_open=False)})

    assert plan.new_candidates == ()
    assert plan.supporting == (closed(),)


def test_disagreeing_observation_conflicts_with_pending_candidate() -> None:
    plan = plan_diff([opened()], {}, {KEY: PendingCandidate(id=7, is_open=False)})

    assert plan.supporting == ()
    assert plan.conflicting == (opened(),)


def test_duplicate_rows_in_one_snapshot_propose_once() -> None:
    plan = plan_diff([closed(), closed("Thanksgiving")], {}, {})

    assert len(plan.new_candidates) == 1
    assert len(plan.supporting) == 2


@pytest.mark.parametrize("rows", [[closed(), opened()], [opened(), closed()]])
def test_contradictory_rows_in_one_snapshot_conflict(rows: list[Observation]) -> None:
    plan = plan_diff(rows, {}, {})

    assert (plan.supporting, plan.conflicting) == ((closed(),), (opened(),))


def test_planning_twice_gives_the_same_plan() -> None:
    observations = [closed()]

    assert plan_diff(observations, {}, {}) == plan_diff(observations, {}, {})


def test_dropped_closure_proposes_reopening_the_date() -> None:
    plan = plan_diff([closed()], PUBLISHED_COLUMBUS_DAY, {})

    reopened = Observation("US-FEDWIRE", COLUMBUS_DAY, True, NOT_LISTED_REASON)
    assert plan.not_listed == (reopened,)
    assert [(c.entry_date, c.before, c.after) for c in plan.new_candidates] == [
        (THANKSGIVING, None, closed()),
        (COLUMBUS_DAY, PUBLISHED_COLUMBUS_DAY[COLUMBUS_KEY], reopened),
    ]
    assert plan.supporting == (closed(), reopened)


@pytest.mark.parametrize(
    "listed",
    [
        Observation("US-FEDWIRE", date(2027, 11, 25), False, "Thanksgiving Day"),
        Observation("US-FEDACH", THANKSGIVING, False, "Thanksgiving Day"),
    ],
    ids=["other-year", "other-calendar"],
)
def test_closure_outside_what_the_snapshot_lists_stays(listed: Observation) -> None:
    plan = plan_diff([listed], PUBLISHED_COLUMBUS_DAY, {})

    assert plan.not_listed == ()
    assert [c.entry_date for c in plan.new_candidates] == [listed.entry_date]


def test_dropped_open_entry_is_no_change() -> None:
    published = {COLUMBUS_KEY: PublishedEntry(True, "Open", Basis.PUBLISHED)}

    plan = plan_diff([closed()], published, {})

    assert (plan.not_listed, [c.entry_date for c in plan.new_candidates]) == ((), [THANKSGIVING])


def test_dropped_closure_already_proposed_only_supports_it() -> None:
    pending = {COLUMBUS_KEY: PendingCandidate(id=7, is_open=True)}

    plan = plan_diff([closed()], PUBLISHED_COLUMBUS_DAY, pending)

    assert [c.entry_date for c in plan.new_candidates] == [THANKSGIVING]
    assert plan.supporting == (closed(), *plan.not_listed)
    assert plan.conflicting == ()


def test_dropped_closure_conflicts_with_a_pending_closure() -> None:
    pending = {COLUMBUS_KEY: PendingCandidate(id=7, is_open=False)}

    plan = plan_diff([closed()], PUBLISHED_COLUMBUS_DAY, pending)

    assert plan.conflicting == plan.not_listed


@pytest.mark.parametrize(
    ("sources", "expected"),
    [
        (0, Corroboration.SINGLE_SOURCE),
        (1, Corroboration.SINGLE_SOURCE),
        (2, Corroboration.CORROBORATED),
        (3, Corroboration.CORROBORATED),
    ],
)
def test_corroboration_needs_two_independent_sources(sources: int, expected: Corroboration) -> None:
    assert corroboration(sources) is expected
