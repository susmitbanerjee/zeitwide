"""The open dataset: GitHub data repository files, Hugging Face Parquet and card, checksums."""

import base64
import csv
import io
import json
from collections.abc import Mapping, Sequence
from copy import deepcopy
from dataclasses import dataclass, field, replace
from datetime import UTC, datetime
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, create_autospec

import httpx
import pyarrow.parquet as pq
import pytest
import yaml
from huggingface_hub import HfApi
from huggingface_hub.errors import RepositoryNotFoundError
from typer.testing import CliRunner

from support.doubles import FakeClock, MemorySnapshotStore, RecordingTelemetry
from zeitwide_pipeline.cli import app
from zeitwide_pipeline.daily import DailyJob, DailyResult
from zeitwide_pipeline.distribution import (
    GITHUB,
    HUGGING_FACE,
    Bundle,
    CardError,
    DataRepository,
    Destinations,
    HfHubStore,
    HubDataset,
    OpenData,
    OpenDataError,
    Outcome,
    dry_run,
    validate_card,
)
from zeitwide_pipeline.distribution.bundle import MANIFEST, SUMS, build_bundles, sha256_hex, verify
from zeitwide_pipeline.distribution.changelog import changes
from zeitwide_pipeline.distribution.publisher import build_for, check_folder
from zeitwide_pipeline.distribution.texts import size_category
from zeitwide_pipeline.github import GitHubError
from zeitwide_pipeline.release import ReleaseExport, render
from zeitwide_pipeline.site import SiteLinks

REPO = Path(__file__).resolve().parents[2]
LINKS = SiteLinks("https://zeitwide.example", "https://api.zeitwide.example")
DEST = Destinations("zeitwide/zeitwide-data", "zeitwide/payment-rail-calendars")
PAGES = {
    "GB-CHAPS": "https://zeitwide.example/calendars/GB-CHAPS",
    "EU-T2": "https://zeitwide.example/calendars/EU-T2",
    "US-FEDNOW": "https://zeitwide.example/calendars/US-FEDNOW",
}


def _version(  # noqa: PLR0913
    day: str,
    reason: str,
    *,
    recorded: str,
    until: str | None = None,
    is_open: bool = False,
    basis: str = "published",
    revision: int = 1,
) -> dict[str, object]:
    return {
        "date": day,
        "is_open": is_open,
        "reason": reason,
        "basis": basis,
        "revision": revision,
        "recorded_from": recorded,
        "recorded_to": until,
        "sources": [
            {
                "source_id": "gov-uk",
                "url": "https://www.gov.uk/bank-holidays",
                "sha256": "0" * 64,
                "fetched_at": "2026-10-01T05:00:00+00:00",
            }
        ],
    }


def _calendar(
    calendar_id: str, name: str, entries: list[dict[str, object]], **rules: object
) -> dict[str, object]:
    return {
        "id": calendar_id,
        "name": name,
        "kind": "payment_rail",
        "timezone": "Europe/London",
        "weekend": ["saturday", "sunday"],
        "closes_on_holidays": True,
        "observance": None,
        "operating_hours": None,
        "revision": 3,
        "entries": entries,
        **rules,
    }


RELEASE: dict[str, object] = {
    "schema_version": 2,
    "release": "2026.11.02.1",
    "as_of": "2026-11-02T06:00:00+00:00",
    "license": "CC BY-NC 4.0",
    "calendars": [
        _calendar(
            "GB-CHAPS",
            "CHAPS",
            [
                _version("2026-12-25", "Christmas Day", recorded="2026-10-01T06:00:00+00:00"),
                # Projected first, then the operator published it with another name.
                _version(
                    "2026-12-28",
                    "Boxing Day",
                    recorded="2026-10-01T06:00:00+00:00",
                    until="2026-10-20T06:00:00+00:00",
                    basis="projected",
                ),
                _version(
                    "2026-12-28",
                    "Boxing Day (substitute day)",
                    recorded="2026-10-20T06:00:00+00:00",
                    until="2026-10-25T06:00:00+00:00",
                    revision=2,
                ),
                _version(
                    "2026-12-28",
                    "Boxing Day (substitute day)",
                    recorded="2026-10-25T06:00:00+00:00",
                    basis="projected",
                    revision=3,
                ),
                # Added, then removed again.
                _version(
                    "2027-01-04",
                    "Extra day",
                    recorded="2026-10-02T06:00:00+00:00",
                    until="2026-10-03T06:00:00+00:00",
                ),
                _version(
                    "2027-01-04",
                    "Working day",
                    recorded="2026-10-03T06:00:00+00:00",
                    is_open=True,
                    revision=2,
                ),
            ],
        ),
        _calendar(
            "EU-T2",
            "T2",
            [_version("2027-01-01", "New Year's Day", recorded="2026-10-02T06:00:00+00:00")],
            timezone="Europe/Berlin",
        ),
        _calendar("US-FEDNOW", "FedNow", [], weekend=[], closes_on_holidays=False, revision=0),
    ],
}
CONTENT = render(RELEASE)
CHECKSUM = sha256_hex(CONTENT)


def _bundles(content: bytes = CONTENT) -> dict[str, Bundle]:
    return {bundle.target: bundle for bundle in build_for(content, LINKS, DEST)}


def _text(bundle: Bundle, path: str) -> str:
    return bundle.file(path).content.decode()


def test_github_bundle_has_the_files_the_repository_needs() -> None:
    bundle = _bundles()[GITHUB]
    # Sorted, with the checksums of everything else last.
    assert bundle.paths[:-1] == sorted(bundle.paths[:-1])
    assert bundle.paths[-1] == SUMS
    assert set(bundle.paths) == {
        "README.md",
        "CITATION.cff",
        "LICENSE",
        "CHANGELOG.md",
        "MANIFEST.json",
        "SHA256SUMS",
        "calendars.csv",
        *(f"data/{c}.{ext}" for c in PAGES for ext in ("csv", "json")),
    }
    readme = _text(bundle, "README.md")
    # One paragraph summary first, then the table of calendars with closure counts.
    lines = readme.splitlines()
    assert lines[0] == "# Zeitwide payment-rail calendars"
    assert lines[2].startswith("Zeitwide publishes the days on which 3 payment-rail")
    assert (
        "| [GB-CHAPS](https://zeitwide.example/calendars/GB-CHAPS) | CHAPS | 2 | 2026-2027 |"
        in (readme)
    )
    assert "| [US-FEDNOW](https://zeitwide.example/calendars/US-FEDNOW) | FedNow | 0 |" in readme
    assert "Each release is tagged `2026.11.02.1`" in readme
    assert "CC BY-NC 4.0" in _text(bundle, "LICENSE")


def test_every_artefact_links_each_calendar_to_its_canonical_page() -> None:
    bundles = _bundles()
    github, hub = bundles[GITHUB], bundles[HUGGING_FACE]
    for page in PAGES.values():
        assert page in _text(github, "README.md")
        assert page in _text(hub, "README.md")
        assert page in _text(github, "calendars.csv")
    for calendar_id, page in PAGES.items():
        assert json.loads(github.file(f"data/{calendar_id}.json").content)["calendar"]["url"] == (
            page
        )
        metadata = pq.read_schema(io.BytesIO(hub.file(f"data/{calendar_id}.parquet").content))
        assert metadata.metadata[b"calendar_url"].decode() == page
    changelog = _text(github, "CHANGELOG.md")
    assert PAGES["GB-CHAPS"] in changelog
    assert "https://zeitwide.example/calendars/GB-CHAPS/2026-12-25" in changelog
    cff = yaml.safe_load(_text(github, "CITATION.cff"))
    assert cff["url"] == "https://zeitwide.example/"


def test_csv_json_and_parquet_hold_the_same_rows() -> None:
    bundles = _bundles()
    github, hub = bundles[GITHUB], bundles[HUGGING_FACE]
    rows = list(csv.DictReader(io.StringIO(_text(github, "data/GB-CHAPS.csv"))))
    assert list(rows[0]) == ["date", "weekday", "open", "reason", "basis", "sources", "revision"]
    assert [(r["date"], r["weekday"], r["open"], r["basis"], r["revision"]) for r in rows] == [
        ("2026-12-25", "Friday", "false", "published", "1"),
        ("2026-12-28", "Monday", "false", "projected", "3"),
        ("2027-01-04", "Monday", "true", "published", "2"),
    ]
    document = json.loads(github.file("data/GB-CHAPS.json").content)
    assert document["dataset_release"] == "2026.11.02.1"
    assert document["license"]["name"] == "CC BY-NC 4.0"
    assert [e["date"] for e in document["entries"]] == [r["date"] for r in rows]
    assert document["entries"][0]["sources"] == ["https://www.gov.uk/bank-holidays"]
    table = pq.read_table(io.BytesIO(hub.file("data/GB-CHAPS.parquet").content))
    assert table.column_names == [
        "date",
        "weekday",
        "open",
        "reason",
        "basis",
        "sources",
        "revision",
    ]
    assert table.column("reason").to_pylist() == [r["reason"] for r in rows]
    assert table.column("open").to_pylist() == [False, False, True]
    assert table.column("sources")[0].as_py() == ["https://www.gov.uk/bank-holidays"]
    # A calendar with no entries still has its (empty) file.
    assert pq.read_table(io.BytesIO(hub.file("data/US-FEDNOW.parquet").content)).num_rows == 0


def test_the_same_release_gives_the_same_bytes_and_the_manifest_matches_the_release() -> None:
    first, second = _bundles(), _bundles()
    for target, bundle in first.items():
        assert [f.sha256 for f in bundle.files] == [f.sha256 for f in second[target].files]
        manifest = json.loads(bundle.file(MANIFEST).content)
        assert manifest["release"] == "2026.11.02.1"
        assert manifest["release_sha256"] == CHECKSUM
        assert verify(bundle, release_sha256=CHECKSUM) == []
        for line in _text(bundle, SUMS).splitlines():
            digest, _, path = line.partition("  ")
            assert bundle.file(path).sha256 == digest
        assert verify(bundle, release_sha256="0" * 64)


def test_verify_names_a_file_that_does_not_match() -> None:
    bundle = _bundles()[GITHUB]
    files = tuple(
        type(f)(f.path, f.content + b"x") if f.path == "data/EU-T2.csv" else f for f in bundle.files
    )
    problems = verify(Bundle(bundle.target, bundle.release, bundle.data_digest, files))
    assert any("data/EU-T2.csv" in problem for problem in problems)


def test_data_digest_follows_the_data_not_the_release_id() -> None:
    base = _bundles()
    renumbered = deepcopy(RELEASE)
    renumbered["release"] = "2026.11.03.1"
    renumbered["as_of"] = "2026-11-03T06:00:00+00:00"
    again = _bundles(render(renumbered))
    for target in base:
        assert again[target].data_digest == base[target].data_digest
        assert again[target].release != base[target].release
    assert base[GITHUB].data_digest == base[HUGGING_FACE].data_digest
    edited = deepcopy(RELEASE)
    calendars = edited["calendars"]
    assert isinstance(calendars, list)
    calendars[1]["entries"][0]["reason"] = "New Year"
    assert _bundles(render(edited))[GITHUB].data_digest != base[GITHUB].data_digest


def test_changelog_lists_each_change_from_the_entry_history() -> None:
    found = [(c.entry_date.isoformat(), c.change.value) for c in changes(RELEASE)]
    assert sorted(found) == sorted(
        [
            ("2026-12-25", "closure_added"),
            ("2026-12-28", "closure_added"),
            ("2027-01-01", "closure_added"),
            ("2027-01-04", "closure_added"),
            ("2026-12-28", "closure_changed"),
            ("2027-01-04", "closure_removed"),
            ("2026-12-28", "basis_changed"),
        ]
    )
    text = _text(_bundles()[GITHUB], "CHANGELOG.md")
    assert text.index("## 2026-10-25") < text.index("## 2026-10-20") < text.index("## 2026-10-01")
    assert "closure changed: Boxing Day -> Boxing Day (substitute day)" in text
    assert "basis changed: published -> projected" in text
    assert "closure removed: open (Working day)" in text
    assert "closure added: Christmas Day (published)" in text
    empty = deepcopy(RELEASE)
    empty["calendars"] = [_calendar("US-FEDNOW", "FedNow", [], closes_on_holidays=False)]
    assert "No changes recorded yet." in _text(_bundles(render(empty))[GITHUB], "CHANGELOG.md")
    with pytest.raises(TypeError):
        changes({"calendars": None})


def test_citation_cff_is_valid_yaml_with_the_fields_github_reads() -> None:
    cff = yaml.safe_load(_text(_bundles()[GITHUB], "CITATION.cff"))
    assert cff["cff-version"] == "1.2.0"
    assert cff["type"] == "dataset"
    assert cff["version"] == "2026.11.02.1"
    assert str(cff["date-released"]) == "2026-11-02"
    assert cff["license"] == "CC-BY-NC-4.0"
    assert cff["authors"] == [{"name": "Zeitwide"}]
    assert cff["repository"] == "https://github.com/zeitwide/zeitwide-data"


def test_dataset_card_validates_with_huggingface_hub() -> None:
    card = _text(_bundles()[HUGGING_FACE], "README.md")
    validate_card(card)
    front = yaml.safe_load(card.split("---")[1])
    assert front["license"] == "cc-by-nc-4.0"
    assert front["size_categories"] == ["n<1K"]
    assert [c["config_name"] for c in front["configs"]] == ["GB-CHAPS", "EU-T2", "US-FEDNOW"]
    for heading in ("## Summary", "## How it is sourced", "## Known limits", "## How to cite"):
        assert heading in card
    assert "```bibtex" in card
    assert size_category(5) == "n<1K"
    assert size_category(5_000) == "1K<n<10K"
    assert size_category(20_000) == "10K<n<100K"
    assert size_category(500_000) == "100K<n<1M"
    assert size_category(5_000_000) == "1M<n<10M"


@pytest.mark.parametrize(
    "card",
    [
        "# No metadata\n\nBody.\n",
        "---\nlicense: cc-by-nc-4.0\n---\n\n# Missing the rest\n",
        "---\nlicense: [unclosed\n---\n\n# Bad YAML\n",
    ],
)
def test_a_bad_dataset_card_is_refused(card: str) -> None:
    with pytest.raises(CardError):
        validate_card(card)


def test_the_hubs_own_validator_is_asked_when_online(monkeypatch: pytest.MonkeyPatch) -> None:
    calls: list[str | None] = []

    def validate(_self: object, repo_type: str | None = None) -> None:
        calls.append(repo_type)

    monkeypatch.setattr("huggingface_hub.DatasetCard.validate", validate)
    card = _text(_bundles()[HUGGING_FACE], "README.md")
    validate_card(card, online=True)
    assert calls == ["dataset"]

    def refuse(_self: object, repo_type: str | None = None) -> None:
        raise ValueError(f"bad {repo_type}")

    monkeypatch.setattr("huggingface_hub.DatasetCard.validate", refuse)
    with pytest.raises(CardError, match="bad dataset"):
        validate_card(card, online=True)


@dataclass
class _GitHub:
    """Just enough of the Git Data API to publish a release."""

    tags: dict[str, str] = field(default_factory=dict)
    manifest: dict[str, object] | None = None
    blobs: dict[str, bytes] = field(default_factory=dict)
    trees: dict[str, list[dict[str, str]]] = field(default_factory=dict)
    commits: list[dict[str, object]] = field(default_factory=list)
    log: list[str] = field(default_factory=list)
    fail_on: str | None = None

    def handle(self, request: httpx.Request) -> httpx.Response:  # noqa: PLR0911
        path = request.url.path.removeprefix("/repos/zeitwide/zeitwide-data")
        self.log.append(f"{request.method} {path}")
        assert request.headers["Authorization"] == "Bearer token"
        if self.fail_on == path:
            return httpx.Response(500, json={"message": "boom"})
        body = json.loads(request.content) if request.content else {}
        if request.method == "GET" and path.startswith("/git/ref/tags/"):
            sha = self.tags.get(path.rsplit("/", 1)[1])
            return _json(200, {"object": {"sha": sha}}) if sha else _json(404, {"message": "no"})
        if request.method == "GET" and path == "":
            return _json(200, {"default_branch": "main"})
        if request.method == "GET" and path == "/git/ref/heads/main":
            return _json(200, {"object": {"sha": "head0"}})
        if request.method == "GET" and path == f"/contents/{MANIFEST}":
            assert "raw" in request.headers["Accept"]
            if self.manifest is None:
                return _json(404, {"message": "Not Found"})
            return httpx.Response(200, content=json.dumps(self.manifest).encode())
        if path == "/git/blobs":
            sha = f"blob{len(self.blobs)}"
            self.blobs[sha] = base64.b64decode(body["content"])
            return _json(201, {"sha": sha})
        if path == "/git/trees":
            self.trees["tree1"] = body["tree"]
            return _json(201, {"sha": "tree1"})
        if path == "/git/commits":
            self.commits.append(body)
            return _json(201, {"sha": "commit1"})
        if request.method == "PATCH" and path == "/git/refs/heads/main":
            assert body == {"sha": "commit1", "force": False}
            return _json(200, {})
        if path == "/git/refs":
            self.tags[body["ref"].removeprefix("refs/tags/")] = body["sha"]
            return _json(201, {})
        return _json(404, {"message": f"unexpected {request.method} {path}"})


def _json(status: int, body: object) -> httpx.Response:
    return httpx.Response(status, json=body)


def _repository(github: _GitHub) -> DataRepository:
    client = httpx.Client(transport=httpx.MockTransport(github.handle))
    return DataRepository(client, "zeitwide/zeitwide-data", "token")


def test_github_publishes_one_commit_of_the_bundle_and_tags_the_release() -> None:
    github = _GitHub(manifest={"release": "2026.10.01.1", "data_digest": "old"})
    bundle = _bundles()[GITHUB]
    assert _repository(github).publish(bundle) is Outcome.PUBLISHED
    assert github.tags == {"2026.11.02.1": "commit1"}
    assert [e["path"] for e in github.trees["tree1"]] == bundle.paths
    assert {sha256_hex(content) for content in github.blobs.values()} == {
        f.sha256 for f in bundle.files
    }
    assert github.commits == [
        {"message": "Dataset release 2026.11.02.1", "tree": "tree1", "parents": ["head0"]}
    ]
    # Run again: the release is tagged, so nothing else is sent.
    github.log.clear()
    assert _repository(github).publish(bundle) is Outcome.ALREADY_PUBLISHED
    assert github.log == ["GET /git/ref/tags/2026.11.02.1"]


def test_github_skips_a_release_with_no_data_change() -> None:
    bundle = _bundles()[GITHUB]
    github = _GitHub(manifest={"release": "2026.10.01.1", "data_digest": bundle.data_digest})
    assert _repository(github).publish(bundle) is Outcome.UNCHANGED
    assert github.commits == []
    assert github.blobs == {}
    assert github.tags == {}


def test_github_publishes_to_an_empty_repository_and_finishes_a_half_done_release() -> None:
    bundle = _bundles()[GITHUB]
    github = _GitHub()
    assert _repository(github).publish(bundle) is Outcome.PUBLISHED
    # The commit landed but the tag didn't: only the tag is missing.
    github = _GitHub(manifest={"release": bundle.release, "data_digest": bundle.data_digest})
    assert _repository(github).publish(bundle) is Outcome.PUBLISHED
    assert github.tags == {bundle.release: "head0"}
    assert github.commits == []


def test_github_reports_errors_without_the_token() -> None:
    github = _GitHub(fail_on="/git/trees")
    with pytest.raises(GitHubError, match="500: boom") as error:
        _repository(github).publish(_bundles()[GITHUB])
    assert "token" not in str(error.value)
    with pytest.raises(ValueError, match="owner/name"):
        DataRepository(httpx.Client(), "not a repository", "token")


@dataclass
class _Hub:
    manifest: str | None = None
    existing: list[str] = field(default_factory=lambda: ["README.md", "stale.parquet"])
    tags: set[str] = field(default_factory=set)
    commits: list[tuple[dict[str, bytes], list[str], str]] = field(default_factory=list)
    tagged: list[tuple[str, str]] = field(default_factory=list)

    def read_text(self, path: str) -> str | None:
        assert path == MANIFEST
        return self.manifest

    def files(self) -> list[str]:
        return self.existing

    def has_tag(self, name: str) -> bool:
        return name in self.tags

    def head(self) -> str:
        return "head0"

    def commit(self, files: Mapping[str, bytes], delete: Sequence[str], message: str) -> str:
        self.commits.append((dict(files), list(delete), message))
        return "commit1"

    def tag(self, name: str, revision: str) -> None:
        self.tagged.append((name, revision))
        self.tags.add(name)


def test_hub_publishes_the_parquet_files_and_removes_stale_ones() -> None:
    bundle = _bundles()[HUGGING_FACE]
    hub = _Hub(manifest=json.dumps({"release": "2026.10.01.1", "data_digest": "old"}))
    assert HubDataset(hub, validate_online=False).publish(bundle) is Outcome.PUBLISHED
    files, deleted, message = hub.commits[0]
    assert set(files) == set(bundle.paths)
    assert deleted == ["stale.parquet"]
    assert message == "Dataset release 2026.11.02.1"
    assert hub.tagged == [("2026.11.02.1", "commit1")]
    assert HubDataset(hub, validate_online=False).publish(bundle) is Outcome.ALREADY_PUBLISHED


def test_hub_skips_unchanged_data_and_finishes_a_half_done_release() -> None:
    bundle = _bundles()[HUGGING_FACE]
    same = json.dumps({"release": "2026.10.01.1", "data_digest": bundle.data_digest})
    hub = _Hub(manifest=same)
    assert HubDataset(hub, validate_online=False).publish(bundle) is Outcome.UNCHANGED
    assert hub.commits == []
    for manifest in ("not json", "[]", None):
        hub = _Hub(manifest=manifest)
        assert HubDataset(hub, validate_online=False).publish(bundle) is Outcome.PUBLISHED
    half = _Hub(manifest=json.dumps({"release": bundle.release}))
    assert HubDataset(half, validate_online=False).publish(bundle) is Outcome.PUBLISHED
    assert half.commits == []
    assert half.tagged == [(bundle.release, "head0")]


def test_hub_refuses_a_release_whose_card_does_not_validate() -> None:
    bundle = _bundles()[HUGGING_FACE]
    broken = Bundle(
        bundle.target,
        bundle.release,
        bundle.data_digest,
        tuple(
            type(f)(f.path, b"# No metadata\n") if f.path == "README.md" else f
            for f in bundle.files
        ),
    )
    hub = _Hub()
    with pytest.raises(CardError):
        HubDataset(hub, validate_online=False).publish(broken)
    assert hub.commits == []


def test_hf_hub_store_talks_to_the_hub_through_huggingface_hub() -> None:
    api = create_autospec(HfApi, instance=True)
    store = HfHubStore(api, "zeitwide/payment-rail-calendars")
    api.hf_hub_download.side_effect = lambda *_a, **kw: _write(kw["local_dir"], "hello")
    assert store.read_text(MANIFEST) == "hello"
    api.hf_hub_download.side_effect = _gone()
    assert store.read_text(MANIFEST) is None
    api.list_repo_files.return_value = ["a", "b"]
    assert store.files() == ["a", "b"]
    api.list_repo_refs.return_value = SimpleNamespace(tags=[SimpleNamespace(name="v1")])
    assert store.has_tag("v1")
    assert not store.has_tag("v2")
    api.dataset_info.return_value = SimpleNamespace(sha="abc")
    assert store.head() == "abc"
    api.dataset_info.return_value = SimpleNamespace(sha=None)
    with pytest.raises(RuntimeError):
        store.head()
    api.create_commit.return_value = SimpleNamespace(oid="commit9")
    assert store.commit({"x.csv": b"1"}, ["old.csv"], "msg") == "commit9"
    api.create_repo.assert_called_once_with(
        "zeitwide/payment-rail-calendars", repo_type="dataset", exist_ok=True
    )
    operations = api.create_commit.call_args.args[1]
    assert [(type(op).__name__, op.path_in_repo) for op in operations] == [
        ("CommitOperationAdd", "x.csv"),
        ("CommitOperationDelete", "old.csv"),
    ]
    store.tag("v2", "commit9")
    api.create_tag.assert_called_once_with(
        "zeitwide/payment-rail-calendars",
        tag="v2",
        revision="commit9",
        repo_type="dataset",
        exist_ok=True,
    )
    api.list_repo_refs.side_effect = _gone()
    api.list_repo_files.side_effect = _gone()
    assert not store.has_tag("v1")
    assert store.files() == []


def _gone() -> RepositoryNotFoundError:
    return RepositoryNotFoundError("gone", response=MagicMock(headers={}))


def _write(folder: str, text: str) -> str:
    target = Path(folder) / "file"
    target.write_text(text, encoding="utf-8")
    return str(target)


@dataclass
class _Target:
    outcome: Outcome | Exception
    seen: list[Bundle] = field(default_factory=list)

    def publish(self, bundle: Bundle) -> Outcome:
        self.seen.append(bundle)
        if isinstance(self.outcome, Exception):
            raise self.outcome
        return self.outcome


def test_open_data_publishes_each_bundle_to_its_target_and_keeps_going_after_a_failure() -> None:
    github = _Target(Outcome.UNCHANGED)
    hub = _Target(Outcome.PUBLISHED)
    both = OpenData(LINKS, DEST, {GITHUB: github, HUGGING_FACE: hub})
    assert both.publish(CONTENT) == {GITHUB: Outcome.UNCHANGED, HUGGING_FACE: Outcome.PUBLISHED}
    assert github.seen[0].target == GITHUB
    assert hub.seen[0].target == HUGGING_FACE
    only_hub = OpenData(LINKS, DEST, {HUGGING_FACE: _Target(Outcome.PUBLISHED)})
    assert only_hub.publish(CONTENT) == {HUGGING_FACE: Outcome.PUBLISHED}
    failing, working = _Target(RuntimeError("down")), _Target(Outcome.PUBLISHED)
    with pytest.raises(OpenDataError, match="github"):
        OpenData(LINKS, DEST, {GITHUB: failing, HUGGING_FACE: working}).publish(CONTENT)
    assert working.seen


def test_dry_run_builds_every_artefact_locally_and_the_checksums_match(tmp_path: Path) -> None:
    bundles = dry_run(CONTENT, tmp_path, LINKS, DEST, expect_sha256=CHECKSUM)
    assert {b.target for b in bundles} == {GITHUB, HUGGING_FACE}
    for bundle in bundles:
        folder = tmp_path / bundle.target
        assert check_folder(folder, bundle) == []
        manifest = json.loads((folder / MANIFEST).read_text(encoding="utf-8"))
        assert manifest["release_sha256"] == CHECKSUM
        for path, digest in manifest["files"].items():
            assert sha256_hex((folder / path).read_bytes()) == digest
    # A file edited after the build no longer matches its checksum.
    (tmp_path / GITHUB / "data" / "EU-T2.csv").write_bytes(b"tampered")
    assert any("EU-T2.csv" in p for p in check_folder(tmp_path / GITHUB, bundles[0]))
    (tmp_path / GITHUB / "data" / "EU-T2.csv").unlink()
    assert any("not written" in p for p in check_folder(tmp_path / GITHUB, bundles[0]))


def test_dry_run_refuses_a_release_that_is_not_the_recorded_one(tmp_path: Path) -> None:
    with pytest.raises(OpenDataError, match="checksum"):
        dry_run(CONTENT, tmp_path, LINKS, DEST, expect_sha256="0" * 64)
    assert not any(tmp_path.iterdir())


def test_the_real_release_builds_into_a_folder_from_the_cli(tmp_path: Path) -> None:
    release = max((REPO / "dataset" / "releases").glob("*.json"))
    checksum = sha256_hex(release.read_bytes())
    result = CliRunner().invoke(
        app,
        [
            "open-data",
            "dry-run",
            "--release",
            str(release),
            "--out",
            str(tmp_path),
            "--site-url",
            "https://zeitwide.example",
            "--api-url",
            "https://api.zeitwide.example",
            "--expect-sha256",
            checksum,
        ],
    )
    assert result.exit_code == 0, result.output
    assert "ok: github" in result.output
    assert "ok: huggingface" in result.output
    names = {p.name for p in (tmp_path / "github" / "data").iterdir()}
    assert {"GB-CHAPS.csv", "GB-CHAPS.json", "US-FEDWIRE.csv"} <= names
    assert {p.suffix for p in (tmp_path / "huggingface" / "data").iterdir()} == {".parquet"}
    readme = (tmp_path / "github" / "README.md").read_text(encoding="utf-8")
    assert "https://zeitwide.example/calendars/GB-CHAPS" in readme
    wrong = CliRunner().invoke(
        app,
        [
            "open-data",
            "dry-run",
            "--release",
            str(release),
            "--out",
            str(tmp_path / "again"),
            "--site-url",
            "https://zeitwide.example",
            "--api-url",
            "https://api.zeitwide.example",
            "--expect-sha256",
            "0" * 64,
        ],
    )
    assert wrong.exit_code == 1
    assert "FAIL" in wrong.output


def test_publishing_from_the_cli_needs_the_open_data_configured(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    for name, value in {
        "ZEITWIDE_BOT_CONTACT_URL": "https://example.test/bot",
        "ZEITWIDE_SNAPSHOT_BUCKET": "snapshots",
    }.items():
        monkeypatch.setenv(name, value)
    for name in ("ZEITWIDE_SITE_URL", "HF_TOKEN", "GITHUB_DATA_TOKEN"):
        monkeypatch.delenv(name, raising=False)
    release = tmp_path / "release.json"
    release.write_bytes(CONTENT)
    result = CliRunner().invoke(app, ["open-data", "publish", "--release", str(release)])
    assert result.exit_code == 1
    assert "not configured" in result.output


def _daily(open_data: OpenData | None) -> DailyJob:
    return DailyJob(
        open_review=lambda: pytest.fail("not used"),
        open_releases=lambda: pytest.fail("not used"),
        run_cleanup=lambda: None,
        releases=MemorySnapshotStore(),
        dataset_repository=None,
        backup=None,
        telemetry=RecordingTelemetry().telemetry,
        clock=FakeClock(),
        open_data=open_data,
    )


def test_daily_job_publishes_the_open_data_with_the_release() -> None:
    target = _Target(Outcome.PUBLISHED)
    export = ReleaseExport("2026.11.02.1", datetime(2026, 11, 2, 6, tzinfo=UTC), {}, CONTENT)
    result = DailyResult()
    _daily(OpenData(LINKS, DEST, {GITHUB: target}))._distribute(export, result)
    assert result.open_data == {GITHUB: Outcome.PUBLISHED}
    assert target.seen[0].release == "2026.11.02.1"
    # Unconfigured: skipped with a warning, the release still distributed.
    skipped = DailyResult()
    _daily(None)._distribute(export, skipped)
    assert skipped.open_data == {}


def test_bundles_build_from_a_release_with_no_closures() -> None:
    empty = deepcopy(RELEASE)
    empty["calendars"] = [_calendar("US-FEDNOW", "FedNow", [], closes_on_holidays=False)]
    bundles = build_bundles(empty, LINKS, DEST, release_sha256=CHECKSUM)
    assert all(verify(bundle) == [] for bundle in bundles)


class _BrokenRepository:
    def propose_release(self, release_id: str, content: bytes) -> str:
        raise GitHubError("GitHub returned 409: Git Repository is empty.")


def test_a_failing_pull_request_does_not_stop_the_open_data() -> None:
    target = _Target(Outcome.PUBLISHED)
    export = ReleaseExport("2026.11.02.1", datetime(2026, 11, 2, 6, tzinfo=UTC), {}, CONTENT)
    job = _daily(OpenData(LINKS, DEST, {GITHUB: target}))
    job = replace(job, dataset_repository=_BrokenRepository())  # type: ignore[arg-type]
    result = DailyResult()
    with pytest.raises(ExceptionGroup) as raised:
        job._distribute(export, result)
    assert isinstance(raised.value.exceptions[0], GitHubError)
    assert result.open_data == {GITHUB: Outcome.PUBLISHED}
