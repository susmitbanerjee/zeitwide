"""The conformance suite's golden dataset stays in step with the real snapshots behind it."""

import json
import os
from datetime import date
from pathlib import Path

import jsonschema
import pytest
from zeitwide.conformance import load_cases, run_cases
from zeitwide.release import parse_release

from support.golden_dataset import PATH, GoldenDatasetError, build, check_nothing_removed

RELEASE_SCHEMA = Path(__file__).parents[2] / "dataset" / "schema" / "release.schema.json"
CASES = Path(__file__).parents[2] / "conformance" / "cases"
REWRITE = "ZEITWIDE_WRITE_GOLDEN_DATASET"


def test_committed_golden_dataset_is_what_the_snapshots_build() -> None:
    built = build()
    if os.environ.get(REWRITE) == "1":
        PATH.write_bytes(built)

    assert PATH.read_bytes() == built, f"Rebuild it: {REWRITE}=1 pytest {Path(__file__).name}"


def test_golden_dataset_matches_the_release_schema() -> None:
    schema = json.loads(RELEASE_SCHEMA.read_text(encoding="utf-8"))

    jsonschema.validate(json.loads(build()), schema)


def test_golden_dataset_passes_every_case_in_the_open_library() -> None:
    results = run_cases(parse_release(build()), load_cases(CASES), golden=True)

    assert [result for result in results if not result.passed] == []


def test_state_funeral_is_recorded_when_gov_uk_first_listed_it() -> None:
    chaps = next(c for c in json.loads(build())["calendars"] if c["id"] == "GB-CHAPS")

    (funeral,) = [e for e in chaps["entries"] if e["date"] == "2022-09-19"]
    assert funeral["recorded_from"] == "2022-09-11T02:30:33+00:00"
    assert funeral["sources"][0]["url"].startswith("https://web.archive.org/web/20220911")


def test_a_closure_dropped_from_a_listed_year_is_an_error() -> None:
    with pytest.raises(GoldenDatasetError, match="disappeared"):
        check_nothing_removed(
            "GB-EAW", {date(2026, 12, 28)}, {date(2026, 12, 25)}, range(2026, 2027)
        )
