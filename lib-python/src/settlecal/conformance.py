"""Runs the shared conformance cases (``conformance/cases/``) against a release.

Against the golden dataset (``conformance/dataset.json``) every case must pass. Against a
real release, a case written against release R holds for R and every later release, and
a case the release answers ``out_of_range`` is not applicable: the release has fewer
years or less history than the golden dataset, and it never guesses.
"""

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date, datetime
from enum import StrEnum
from pathlib import Path

from settlecal.engine import (
    Combine,
    OutOfRangeError,
    Shifted,
    add_business_days,
    is_business_day,
    next_business_day,
)
from settlecal.release import Release, UnknownCalendarError, release_version


class Outcome(StrEnum):
    PASSED = "passed"
    FAILED = "failed"
    NOT_APPLICABLE = "not_applicable"


@dataclass(frozen=True, slots=True)
class CaseResult:
    case_id: str
    outcome: Outcome
    detail: str = ""

    @property
    def passed(self) -> bool:
        return self.outcome is Outcome.PASSED


def load_cases(folder: Path) -> list[dict[str, object]]:
    return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(folder.glob("*.json"))]


def run_cases(
    release: Release, cases: Sequence[Mapping[str, object]], *, golden: bool = False
) -> list[CaseResult]:
    """Results for every case that applies to ``release``; with ``golden``, every case."""
    return [
        run_case(release, case, golden=golden)
        for case in cases
        if golden or release_version(str(case["dataset_release"])) <= release.version
    ]


def run_case(release: Release, case: Mapping[str, object], *, golden: bool = False) -> CaseResult:
    case_id = str(case["id"])
    request, expected = case["input"], case["expected"]
    if not isinstance(request, dict) or not isinstance(expected, dict):
        return CaseResult(case_id, Outcome.FAILED, "input and expected must be objects")
    actual = _answer(release, request)
    if actual == expected:
        return CaseResult(case_id, Outcome.PASSED)
    if not golden and actual == {"error": "out_of_range"}:
        return CaseResult(case_id, Outcome.NOT_APPLICABLE, "outside this release's coverage")
    return CaseResult(case_id, Outcome.FAILED, f"expected {expected}, got {actual}")


def _answer(release: Release, request: Mapping[str, object]) -> dict[str, object]:
    calendars = request["calendars"]
    if not isinstance(calendars, list):
        raise TypeError("calendars must be a list")
    ids = [str(calendar) for calendar in calendars]
    day = date.fromisoformat(str(request["date"]))
    combine = Combine(str(request.get("combine", Combine.ALL_OPEN)))
    as_of = datetime.fromisoformat(str(request["as_of"])) if "as_of" in request else None
    operation = str(request["operation"])
    try:
        if operation == "is_business_day":
            return {"is_business_day": is_business_day(release, ids, day, combine, as_of=as_of)}
        if operation == "next_business_day":
            return _shifted(next_business_day(release, ids, day, combine, as_of=as_of))
        days = int(str(request["days"]))
        return _shifted(add_business_days(release, ids, day, days, combine, as_of=as_of))
    except OutOfRangeError:
        return {"error": "out_of_range"}
    except UnknownCalendarError:
        return {"error": "unknown_calendar"}


def _shifted(shifted: Shifted) -> dict[str, object]:
    return {
        "date": shifted.date.isoformat(),
        "skipped": [skipped.isoformat() for skipped in shifted.skipped],
    }
