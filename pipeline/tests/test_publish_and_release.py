import base64
import json
from collections.abc import Iterator, Mapping, Sequence
from datetime import UTC, date, datetime
from pathlib import Path

import httpx
import pytest
import respx

from support.doubles import START, FakeClock, MemorySnapshotStore
from zeitwide_pipeline.backup import DatabaseBackup
from zeitwide_pipeline.calendars import load_catalog
from zeitwide_pipeline.diff import PublishedEntry, plan_diff
from zeitwide_pipeline.github import DatasetRepository, GitHubError
from zeitwide_pipeline.models import Basis, ChangeType, Observation
from zeitwide_pipeline.publish import (
    ChangeEvent,
    EntryState,
    basis_for,
    change_type,
    is_urgent,
    notice_days,
)
from zeitwide_pipeline.release import (
    ReleaseRecord,
    next_release_id,
    release_object_key,
    render,
    revisions_changed,
)
from zeitwide_pipeline.review_db import Review
from zeitwide_pipeline.seed import Evidence, plan_seed

CLOSED = EntryState(is_open=False, reason="Thanksgiving Day", basis=Basis.PUBLISHED)
DAY = date(2026, 11, 26)
REPOSITORY = "zeitwide/dataset"
API = f"https://api.github.com/repos/{REPOSITORY}"


@pytest.mark.parametrize(
    ("before", "after", "expected"),
    [
        (None, CLOSED, ChangeType.CLOSURE_ADDED),
        (EntryState(True, "Open", Basis.PUBLISHED), CLOSED, ChangeType.CLOSURE_ADDED),
        (CLOSED, EntryState(True, "Reopened", Basis.PUBLISHED), ChangeType.CLOSURE_REMOVED),
        (CLOSED, EntryState(False, "Thanksgiving", Basis.PUBLISHED), ChangeType.CLOSURE_CHANGED),
        (EntryState(False, "Thanksgiving Day", Basis.PROJECTED), CLOSED, ChangeType.BASIS_CHANGED),
    ],
)
def test_change_type(before: EntryState | None, after: EntryState, expected: ChangeType) -> None:
    assert change_type(before, after) is expected


def test_identical_version_is_not_a_change() -> None:
    with pytest.raises(ValueError, match="same"):
        change_type(CLOSED, CLOSED)


def test_basis_is_published_only_when_the_operator_listed_the_date() -> None:
    assert (
        basis_for(
            ["federalreserve-k8", "frbservices-holiday-schedules"],
            ["frbservices-holiday-schedules"],
        )
        is Basis.PUBLISHED
    )
    assert basis_for(["govuk-bank-holidays"], []) is Basis.PROJECTED


def test_notice_days_and_urgency() -> None:
    assert notice_days(DAY, date(2026, 11, 1)) == 25
    assert notice_days(DAY, date(2026, 11, 30)) == -4
    assert is_urgent(DAY, date(2026, 11, 13))
    assert not is_urgent(DAY, date(2026, 11, 12))


def test_change_event_payload() -> None:
    event = ChangeEvent(7, ChangeType.CLOSURE_ADDED, "US-FEDWIRE", DAY, None, CLOSED, 3, START)

    assert event.payload() == {
        "event_id": 7,
        "type": "closure_added",
        "calendar_id": "US-FEDWIRE",
        "date": "2026-11-26",
        "before": None,
        "after": {"is_open": False, "reason": "Thanksgiving Day", "basis": "published"},
        "revision": 3,
        "published_at": START.isoformat(),
    }


@pytest.mark.parametrize(("reviewer", "reason"), [("", "ok"), ("alice", " "), ("x" * 256, "ok")])
def test_review_needs_a_reviewer_and_a_reason(reviewer: str, reason: str) -> None:
    with pytest.raises(ValueError, match="characters"):
        Review(reviewer=reviewer, reason=reason)


def test_operator_confirming_a_projected_entry_proposes_publishing_it() -> None:
    projected = {("GB-CHAPS", DAY): PublishedEntry(False, "Christmas", Basis.PROJECTED)}
    observation = Observation("GB-CHAPS", DAY, False, "Christmas Day")

    assert plan_diff([observation], projected, {}).new_candidates == ()
    assert len(plan_diff([observation], projected, {}, frozenset({"GB-CHAPS"})).new_candidates) == 1


def test_release_ids_count_up_within_a_day() -> None:
    assert next_release_id(date(2026, 10, 28), []) == "2026.10.28.1"
    assert next_release_id(date(2026, 10, 28), ["2026.10.28.1", "2026.10.28.2"]) == "2026.10.28.3"
    assert next_release_id(date(2026, 10, 29), ["2026.10.28.4"]) == "2026.10.29.1"
    assert release_object_key("2026.10.28.1") == "releases/2026.10.28.1.json"


def test_release_is_cut_only_when_a_revision_changed() -> None:
    latest = ReleaseRecord("2026.10.28.1", START, {"US-FEDWIRE": 2}, "00" * 32)

    assert revisions_changed({"US-FEDWIRE": 2}, None)
    assert not revisions_changed({"US-FEDWIRE": 2}, latest)
    assert revisions_changed({"US-FEDWIRE": 3}, latest)


def test_render_is_stable_for_the_same_document() -> None:
    assert (
        render({"b": 1, "a": [2]})
        == render({"a": [2], "b": 1})
        == b'{\n  "a": [\n    2\n  ],\n  "b": 1\n}\n'
    )


def test_seed_plan_uses_the_operator_reason_and_basis() -> None:
    fedwire = load_catalog()["US-FEDWIRE"]
    evidence = {
        DAY: [
            Evidence("federalreserve-k8", 2, False, "Thanksgiving"),
            Evidence("frbservices-holiday-schedules", 1, False, "Thanksgiving Day"),
        ],
        date(2026, 11, 11): [Evidence("frbservices-holiday-schedules", 1, True, "Open")],
        date(2028, 11, 23): [
            Evidence("frbservices-holiday-schedules", 1, False, "Thanksgiving Day")
        ],
    }

    entries, conflicts = plan_seed(fedwire, evidence, frozenset({2026}), frozenset())

    assert [(e.entry_date, e.state, e.snapshot_ids) for e in entries] == [
        (DAY, EntryState(False, "Thanksgiving Day", Basis.PUBLISHED), (1, 2))
    ]
    assert conflicts == []


def test_seed_plan_skips_existing_and_flags_disagreements() -> None:
    chaps = load_catalog()["GB-CHAPS"]
    evidence = {
        DAY: [
            Evidence("govuk-bank-holidays", 1, False, "Holiday"),
            Evidence("other", 2, True, "Open"),
        ],
        date(2026, 12, 25): [Evidence("govuk-bank-holidays", 1, False, "Christmas Day")],
    }

    entries, conflicts = plan_seed(
        chaps, evidence, frozenset({2026}), frozenset({date(2026, 12, 25)})
    )

    assert (entries, conflicts) == ([], [DAY])


@pytest.fixture
def github() -> Iterator[respx.MockRouter]:
    with respx.mock(assert_all_called=False) as router:
        yield router


def test_release_pull_request_adds_the_file_on_its_own_branch(github: respx.MockRouter) -> None:
    github.get(
        f"{API}/pulls", params={"state": "all", "head": "zeitwide:release/2026.10.28.1"}
    ).respond(200, json=[])
    github.get(API).respond(200, json={"default_branch": "main"})
    github.get(f"{API}/git/ref/heads/main").respond(200, json={"object": {"sha": "abc"}})
    branch = github.post(f"{API}/git/refs").respond(201, json={})
    put = github.put(f"{API}/contents/releases/2026.10.28.1.json").respond(201, json={})
    github.post(f"{API}/pulls").respond(
        201, json={"html_url": "https://github.com/zeitwide/dataset/pull/1"}
    )

    with httpx.Client() as client:
        url = DatasetRepository(client, REPOSITORY, "token").propose_release(
            "2026.10.28.1", b"{}\n"
        )

    assert url == "https://github.com/zeitwide/dataset/pull/1"
    assert json.loads(branch.calls.last.request.content) == {
        "ref": "refs/heads/release/2026.10.28.1",
        "sha": "abc",
    }
    body = json.loads(put.calls.last.request.content)
    assert (base64.b64decode(body["content"]), body["branch"]) == (b"{}\n", "release/2026.10.28.1")
    assert put.calls.last.request.headers["Authorization"] == "Bearer token"


def test_existing_pull_request_is_reused(github: respx.MockRouter) -> None:
    github.get(f"{API}/pulls").respond(200, json=[{"html_url": "https://github.com/x/pull/9"}])
    opened = github.post(f"{API}/pulls")

    with httpx.Client() as client:
        url = DatasetRepository(client, REPOSITORY, "token").propose_release("2026.10.28.1", b"{}")

    assert (url, opened.called) == ("https://github.com/x/pull/9", False)


def test_github_error_does_not_leak_the_token(github: respx.MockRouter) -> None:
    github.get(f"{API}/pulls").respond(401, json={"message": "Bad credentials"})

    with httpx.Client() as client, pytest.raises(GitHubError) as error:
        DatasetRepository(client, REPOSITORY, "secret-token").propose_release("2026.10.28.1", b"{}")

    assert "Bad credentials" in str(error.value)
    assert "secret-token" not in str(error.value)


def test_malformed_repository_is_rejected() -> None:
    with httpx.Client() as client, pytest.raises(ValueError, match="owner/name"):
        DatasetRepository(client, "not a repo", "token")


def test_backup_dumps_each_database_without_a_shell() -> None:
    calls: list[tuple[list[str], str]] = []

    def runner(args: Sequence[str], env: Mapping[str, str], output: Path) -> None:
        calls.append((list(args), env["PGPASSWORD"]))
        output.write_bytes(b"dump")

    store = MemorySnapshotStore()
    backup = DatabaseBackup(
        store,
        FakeClock(datetime(2026, 10, 28, 3, 15, tzinfo=UTC)),
        user="zeitwide_backup",
        password="pw",
        databases=["zeitwide", "keycloak"],
        runner=runner,
    )

    keys = backup.run()

    assert keys == [
        "pg_dump/zeitwide/20261028T031500Z.dump",
        "pg_dump/keycloak/20261028T031500Z.dump",
    ]
    assert calls[0] == (
        ["pg_dump", "--format=custom", "--no-owner", "--no-privileges", "--dbname=zeitwide"],
        "pw",
    )
    assert store.objects[keys[1]] == (b"dump", "application/octet-stream")
