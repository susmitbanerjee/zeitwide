from datetime import date

import pytest

from settlecal_pipeline.diff import (
    PendingCandidate,
    PublishedEntry,
    corroboration,
    plan_diff,
)
from settlecal_pipeline.models import Basis, Corroboration, Observation

THANKSGIVING = date(2026, 11, 26)
KEY = ("US-FEDWIRE", THANKSGIVING)


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
