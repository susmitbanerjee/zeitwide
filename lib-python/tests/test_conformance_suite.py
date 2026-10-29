"""The shared conformance suite: every case against the golden dataset, and the cases that
apply against every release in ``dataset/releases/``.

The pipeline's end-to-end test also runs the suite against a release it builds from the
real source snapshots, so the cases are checked even before a release is committed.
"""

import json
from pathlib import Path

import jsonschema
import pytest

from settlecal import load_release
from settlecal.conformance import Outcome, load_cases, run_cases

REPO_ROOT = Path(__file__).parents[2]
CASES = REPO_ROOT / "conformance" / "cases"
CASE_SCHEMA = REPO_ROOT / "conformance" / "case.schema.json"
GOLDEN_DATASET = REPO_ROOT / "conformance" / "dataset.json"
RELEASE_SCHEMA = REPO_ROOT / "dataset" / "schema" / "release.schema.json"
RELEASES = sorted((REPO_ROOT / "dataset" / "releases").glob("*.json"))


def test_every_case_matches_the_schema() -> None:
    schema = json.loads(CASE_SCHEMA.read_text(encoding="utf-8"))
    cases = load_cases(CASES)

    assert cases
    for case in cases:
        jsonschema.validate(case, schema)


def test_case_ids_match_file_names() -> None:
    for path in CASES.glob("*.json"):
        assert json.loads(path.read_text(encoding="utf-8"))["id"] == path.stem


def test_golden_dataset_matches_the_release_schema() -> None:
    schema = json.loads(RELEASE_SCHEMA.read_text(encoding="utf-8"))

    jsonschema.validate(json.loads(GOLDEN_DATASET.read_bytes()), schema)


def test_golden_dataset_passes_every_case() -> None:
    results = run_cases(load_release(GOLDEN_DATASET), load_cases(CASES), golden=True)

    assert len(results) == len(list(CASES.glob("*.json")))
    assert [result for result in results if result.outcome is not Outcome.PASSED] == []


@pytest.mark.skipif(not RELEASES, reason="no release in dataset/releases/ yet")
@pytest.mark.parametrize("path", RELEASES, ids=lambda path: path.stem)
def test_release_passes_every_case_that_applies(path: Path) -> None:
    results = run_cases(load_release(path), load_cases(CASES))

    assert [result for result in results if result.outcome is Outcome.FAILED] == []
