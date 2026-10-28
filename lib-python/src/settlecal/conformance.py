"""Runs the shared conformance cases (``conformance/cases/``) against a release.

A case written against release R also holds for every later release: cases describe
facts, and a fact that changes means the case is out of date, not the library.
"""

import json
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from datetime import date
from pathlib import Path

from settlecal.engine import Combine, add_business_days, is_business_day
from settlecal.release import Release, release_version


@dataclass(frozen=True, slots=True)
class CaseResult:
    case_id: str
    passed: bool
    detail: str = ""


def load_cases(folder: Path) -> list[dict[str, object]]:
    return [json.loads(path.read_text(encoding="utf-8")) for path in sorted(folder.glob("*.json"))]


def run_cases(release: Release, cases: Sequence[Mapping[str, object]]) -> list[CaseResult]:
    """Results for every case that applies to ``release``."""
    return [
        run_case(release, case)
        for case in cases
        if release_version(str(case["dataset_release"])) <= release.version
    ]


def run_case(release: Release, case: Mapping[str, object]) -> CaseResult:
    case_id = str(case["id"])
    request = case["input"]
    expected = case["expected"]
    if not isinstance(request, dict) or not isinstance(expected, dict):
        return CaseResult(case_id, False, "input and expected must be objects")
    if "as_of" in request:
        return CaseResult(case_id, False, "as_of answers need the API's history, not a release")
    calendars = [str(calendar) for calendar in request["calendars"]]
    day = date.fromisoformat(str(request["date"]))
    combine = Combine(str(request.get("combine", Combine.ALL_OPEN)))
    if request["operation"] == "is_business_day":
        actual: dict[str, object] = {
            "is_business_day": is_business_day(release, calendars, day, combine)
        }
    else:
        shifted = add_business_days(release, calendars, day, int(request["days"]), combine)
        actual = {
            "date": shifted.date.isoformat(),
            "skipped": [skipped.isoformat() for skipped in shifted.skipped],
        }
    mismatches = {
        key: (value, actual.get(key)) for key, value in expected.items() if actual.get(key) != value
    }
    if mismatches:
        return CaseResult(case_id, False, f"expected vs actual: {mismatches}")
    return CaseResult(case_id, True)
