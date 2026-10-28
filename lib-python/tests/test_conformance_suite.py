"""The shared conformance suite against every release in ``dataset/releases/``.

The pipeline's end-to-end test also runs the suite against a release it builds from the
real source snapshots, so the cases are checked even before a release is committed.
"""

import json
from pathlib import Path

import jsonschema
import pytest

from settlecal import load_release
from settlecal.conformance import load_cases, run_cases

REPO_ROOT = Path(__file__).parents[2]
CASES = REPO_ROOT / "conformance" / "cases"
CASE_SCHEMA = REPO_ROOT / "conformance" / "case.schema.json"
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


@pytest.mark.skipif(not RELEASES, reason="no release in dataset/releases/ yet")
@pytest.mark.parametrize("path", RELEASES, ids=lambda path: path.stem)
def test_release_passes_every_case(path: Path) -> None:
    results = run_cases(load_release(path), load_cases(CASES))

    assert [result for result in results if not result.passed] == []
