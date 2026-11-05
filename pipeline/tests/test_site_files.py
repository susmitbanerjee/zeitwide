"""The site files: llms.txt, llms-full.txt, sitemaps and robots.txt from a release."""

import json
from collections.abc import Sequence
from dataclasses import dataclass, field
from datetime import UTC, datetime
from pathlib import Path

import httpx
import pytest
from google.api_core.exceptions import PreconditionFailed
from typer.testing import CliRunner

from support.doubles import FakeClock, MemorySnapshotStore, RecordingTelemetry
from zeitwide_pipeline.cli import app
from zeitwide_pipeline.daily import DailyJob, DailyResult
from zeitwide_pipeline.release import ReleaseExport, render
from zeitwide_pipeline.site import CACHE_CONTROL, SiteFile, SiteLinks, build_site_files
from zeitwide_pipeline.site.api_reference import api_reference
from zeitwide_pipeline.site.docs import Doc, load_docs, load_openapi, parse_doc
from zeitwide_pipeline.site.release_view import read_release
from zeitwide_pipeline.site.robots import load_robots_config
from zeitwide_pipeline.site.store import DirectorySiteFileStore, GcsSiteFileStore
from zeitwide_pipeline.site.validate import (
    check_llms_txt,
    check_sitemap,
    closure_rows,
    llms_links,
    validate_site,
)

REPO = Path(__file__).resolve().parents[2]
LINKS = SiteLinks("https://zeitwide.example", "https://api.zeitwide.example")
DOCS = [
    parse_doc(
        "index",
        "---\ntitle: Developer quickstart\ndescription: Call the API.\nupdated: 2026-11-02\n---\n"
        "# Developer quickstart\n\nHello.\n",
    )
]
OPENAPI: dict[str, object] = {
    "info": {"title": "Zeitwide API", "version": "1"},
    "paths": {
        "/v1/calendars/{id}/closures": {
            "get": {
                "summary": "The closures on one calendar",
                "parameters": [{"$ref": "#/components/parameters/Id"}],
                "responses": {
                    "200": {
                        "description": "The closures | in order.",
                        "content": {"application/json": {"schema": {"$ref": "#/x/ClosureList"}}},
                    }
                },
            }
        }
    },
    "components": {
        "parameters": {"Id": {"name": "id", "in": "path", "required": True}},
        "schemas": {
            "Basis": {"type": "string", "enum": ["published", "projected"]},
            "Closure": {
                "type": "object",
                "required": ["date"],
                "properties": {
                    "date": {"type": "string", "description": "The day."},
                    "sources": {"type": "array", "items": {"$ref": "#/c/Source"}},
                },
            },
        },
    },
}


def _entry(
    day: str, reason: str, *, recorded: str = "2026-10-01T06:00:00+00:00"
) -> dict[str, object]:
    return {
        "date": day,
        "is_open": False,
        "reason": reason,
        "basis": "published",
        "revision": 2,
        "recorded_from": recorded,
        "recorded_to": None,
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
        "kind": "rail",
        "timezone": "Europe/London",
        "weekend": ["saturday", "sunday"],
        "closes_on_holidays": True,
        "observance": None,
        "operating_hours": None,
        "revision": 2,
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
                _entry("2026-12-25", "Christmas Day"),
                _entry(
                    "2026-12-28",
                    "Boxing Day (substitute day)",
                    recorded="2026-10-20T06:00:00+00:00",
                ),
                # Replaced before as_of: not current, not listed.
                {**_entry("2027-01-01", "Wrong"), "recorded_to": "2026-10-15T06:00:00+00:00"},
                _entry("2027-01-01", "New Year's Day", recorded="2026-10-15T06:00:00+00:00"),
            ],
        ),
        _calendar(
            "EU-T2",
            "T2",
            [_entry("2026-12-25", "Christmas Day"), _entry("2027-01-01", "New Year's Day")],
            timezone="Europe/Berlin",
        ),
        _calendar("US-FEDNOW", "FedNow", [], weekend=[], closes_on_holidays=False, revision=0),
    ],
}


def _files(release: dict[str, object] = RELEASE) -> dict[str, str]:
    files = build_site_files(release, LINKS, docs=DOCS, openapi=OPENAPI)
    return {file.name: file.content.decode() for file in files}


def test_same_release_gives_the_same_bytes() -> None:
    assert _files() == _files()


def test_llms_txt_follows_the_format_with_facts_from_the_release() -> None:
    llms = _files()["llms.txt"]
    assert check_llms_txt(llms) == []
    assert llms.startswith("# Zeitwide\n\n> Zeitwide answers")
    assert (
        "Dataset release 2026.11.02.1 (as of 2026-11-02) holds 5 closures from 2026 to 2027."
        in llms
    )
    assert (
        "- [Is Friday 25 December 2026 a CHAPS business day?]"
        "(https://zeitwide.example/calendars/GB-CHAPS/2026-12-25.md): No; CHAPS is closed for "
        "Christmas Day (published, source www.gov.uk)."
    ) in llms
    assert (
        "- [T2 vs CHAPS in 2026](https://zeitwide.example/compare/EU-T2-vs-GB-CHAPS/2026.md): "
        in llms
    )
    assert "T2 closes on 1 day and CHAPS on 2; they share 1." in llms
    assert (
        "- [CHAPS closures in 2027](https://zeitwide.example/calendars/GB-CHAPS/2027.md): "
        "1 closing day, 1 on a weekday." in llms
    )
    assert "names 6 AI search or user-fetch agents" in llms
    assert len(llms.encode()) < 10 * 1024


def test_llms_txt_checks_catch_broken_structure() -> None:
    assert check_llms_txt("# Zeitwide\n\nNo quote\n\n## Answers\n\n- not a link\n- [a](b)\n") == [
        "the line after the H1 must be the blockquote summary",
        "H2 sections are ['Answers'], expected "
        "['Answers', 'Calendars', 'Compare', 'Docs', 'Discovery', 'Optional']",
        "bullet is not a link: - not a link",
        "link has no description: b",
    ]
    assert "over 10240" in check_llms_txt("# Zeitwide\n" + "x" * 11_000)[0]


def test_llms_full_txt_has_one_row_per_current_closure() -> None:
    full = _files()["llms-full.txt"]
    assert closure_rows(full) == {"GB-CHAPS": 3, "EU-T2": 2, "US-FEDNOW": 0}
    assert (
        "| 2026-12-28 | Monday | Boxing Day (substitute day) | published "
        "| <https://www.gov.uk/bank-holidays> |" in full
    )
    assert "Wrong" not in full
    assert "> Release 2026.11.02.1. Generated 2026-11-02T06:00:00Z" in full
    assert "# Developer quickstart" in full
    assert "## GET /v1/calendars/{id}/closures" in full


def test_docs_full_txt_has_docs_and_the_api_reference_but_no_closures() -> None:
    docs = _files()["docs/llms-full.txt"]
    assert "# Developer quickstart" in docs
    assert "# API reference" in docs
    assert "Closures by calendar" not in docs


def test_sitemaps_validate_and_date_pages_by_the_calendars_last_change() -> None:
    files = _files()
    assert check_sitemap(files["sitemap.xml"], index=True) == []
    for name in ("sitemaps/pages.xml", "sitemaps/GB-CHAPS.xml", "sitemaps/US-FEDNOW.xml"):
        assert check_sitemap(files[name], index=False) == []
    chaps = files["sitemaps/GB-CHAPS.xml"]
    # The latest version recorded (20 Oct), not when the files were built.
    assert (
        "<loc>https://zeitwide.example/calendars/GB-CHAPS/2026-12-28</loc><lastmod>2026-10-20</lastmod>"
        in chaps
    )
    # Calendar page, two year pages, then 2 Nov 2026 to 31 Jan 2027, which holds every closure.
    assert chaps.count("<url>") == 1 + 2 + 91
    assert "/calendars/GB-CHAPS/2027-01-31<" in chaps
    assert "/calendars/GB-CHAPS/2027-02-01<" not in chaps
    assert (
        "<loc>https://zeitwide.example/sitemaps/GB-CHAPS.xml</loc><lastmod>2026-10-20</lastmod>"
        in files["sitemap.xml"]
    )
    # FedNow never changed: no lastmod, and it covers only the release's year and the next.
    fednow = files["sitemaps/US-FEDNOW.xml"]
    assert "<lastmod>" not in fednow
    assert "/calendars/US-FEDNOW/2028" not in fednow
    assert (
        "<loc>https://zeitwide.example/docs</loc><lastmod>2026-11-02</lastmod>"
        in files["sitemaps/pages.xml"]
    )


def test_sitemap_check_rejects_what_the_schema_forbids() -> None:
    bad = '<urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9"><url><lastmod>x</lastmod></url></urlset>'
    assert check_sitemap(bad, index=False) != []


def test_robots_txt_names_the_agents_from_config() -> None:
    robots = _files()["robots.txt"]
    assert robots.startswith(
        "User-agent: *\nAllow: /\nDisallow: /app/\nDisallow: /internal/\nDisallow: /api/keys\n"
    )
    for agent in (
        "OAI-SearchBot",
        "Claude-SearchBot",
        "PerplexityBot",
        "ChatGPT-User",
        "Claude-User",
        "Perplexity-User",
    ):
        assert f"User-agent: {agent}\n" in robots
    assert (
        "Sitemap: https://zeitwide.example/sitemap.xml\n# llms.txt: https://zeitwide.example/llms.txt\n"
        in robots
    )
    custom = load_robots_config('disallow = ["/x/"]\n[[groups]]\ncomment = "c"\nagents = ["Bot"]\n')
    assert custom.named_agents == 1


def test_api_reference_lists_operations_and_schemas() -> None:
    reference = api_reference(OPENAPI)
    assert "| `id` | path | yes |  |" in reference
    assert "| 200 | The closures \\| in order. (ClosureList) |" in reference
    assert "| `sources` | array of Source | no |  |" in reference
    assert "One of: `published`, `projected`" in reference


def test_docs_need_front_matter() -> None:
    with pytest.raises(ValueError, match="no front matter"):
        parse_doc("x", "# Title\n")
    with pytest.raises(ValueError, match="no description"):
        parse_doc("x", "---\ntitle: T\nupdated: 2026-01-01\n---\nbody\n")
    assert DOCS[0].path == "/docs"
    assert Doc("rules", "R", "d", "2026-01-01", "").path == "/docs/rules"


def test_synced_copies_match_their_sources() -> None:
    """`make sync-docs` copies docs/site and the OpenAPI document into this package."""
    source = {
        path.stem: path.read_text(encoding="utf-8")
        for path in (REPO / "docs" / "site").glob("*.md")
    }
    packaged = {doc.slug for doc in load_docs()}
    assert packaged == set(source), "run make sync-docs"
    for doc in load_docs():
        assert parse_doc(doc.slug, source[doc.slug]) == doc, "run make sync-docs"
    assert load_openapi() == json.loads(
        (REPO / "web" / "src" / "api" / "openapi.json").read_text(encoding="utf-8")
    )


def test_the_real_release_and_docs_make_valid_files() -> None:
    release = json.loads(
        (REPO / "dataset" / "releases" / "2026.10.29.1.json").read_text(encoding="utf-8")
    )
    files = {file.name: file.content.decode() for file in build_site_files(release, LINKS)}
    assert check_llms_txt(files["llms.txt"]) == []
    view = read_release(release)
    assert closure_rows(files["llms-full.txt"]) == {c.id: len(c.closures) for c in view.calendars}


def test_downloads_hold_each_calendars_current_entries() -> None:
    files = {file.name: file for file in build_site_files(RELEASE, LINKS)}

    csv_file = files["downloads/GB-CHAPS.csv"]
    assert csv_file.content_type == "text/csv; charset=utf-8"
    assert csv_file.content.decode().splitlines() == [
        "date,is_open,reason,basis,source_urls",
        "2026-12-25,false,Christmas Day,published,https://www.gov.uk/bank-holidays",
        "2026-12-28,false,Boxing Day (substitute day),published,https://www.gov.uk/bank-holidays",
        "2027-01-01,false,New Year's Day,published,https://www.gov.uk/bank-holidays",
    ]

    document = json.loads(files["downloads/GB-CHAPS.json"].content)
    assert document["dataset_release"] == "2026.11.02.1"
    assert document["license"]["name"] == "CC BY-NC 4.0"
    assert document["calendar"]["coverage"] == {
        "type": "years",
        "from": "2026-01-01",
        "to": "2027-12-31",
    }
    assert [entry["date"] for entry in document["entries"]] == [
        "2026-12-25",
        "2026-12-28",
        "2027-01-01",
    ]
    fednow = json.loads(files["downloads/US-FEDNOW.json"].content)
    assert fednow["calendar"]["coverage"] == {"type": "unlimited"}
    assert fednow["entries"] == []


def test_ics_has_one_all_day_event_per_closure_with_escaped_folded_lines() -> None:
    long_reason = "Boxing Day, observed; " + "very long holiday name " * 4
    release = {
        **RELEASE,
        "calendars": [_calendar("GB-CHAPS", "CHAPS", [_entry("2026-12-28", long_reason)])],
    }
    files = {file.name: file for file in build_site_files(release, LINKS)}
    raw = files["downloads/GB-CHAPS.ics"].content

    lines = raw.decode().split("\r\n")
    assert lines[0] == "BEGIN:VCALENDAR"
    assert lines[-2:] == ["END:VCALENDAR", ""]
    assert all(len(line.encode()) <= 75 for line in lines)
    assert "DTSTART;VALUE=DATE:20261228" in lines
    assert "DTEND;VALUE=DATE:20261229" in lines
    assert "DTSTAMP:20261102T060000Z" in lines
    unfolded = raw.decode().replace("\r\n ", "")
    assert "SUMMARY:CHAPS closed: Boxing Day\\, observed\\; very long" in unfolded
    assert "URL:https://zeitwide.example/calendars/GB-CHAPS/2026-12-28" in unfolded


@dataclass
class _Blob:
    name: str
    bucket: "_Bucket"
    cache_control: str | None = None

    def upload_from_string(
        self, content: bytes, content_type: str, if_generation_match: int | None = None
    ) -> None:
        if if_generation_match == 0 and self.name in self.bucket.objects:
            raise PreconditionFailed("exists")  # type: ignore[no-untyped-call]
        self.bucket.objects[self.name] = (content, content_type, self.cache_control)


@dataclass
class _Bucket:
    objects: dict[str, tuple[bytes, str, str | None]] = field(default_factory=dict)

    def blob(self, name: str) -> _Blob:
        return _Blob(name, self)


class _Client:
    def __init__(self) -> None:
        self.bucket_ = _Bucket()

    def bucket(self, _name: str) -> _Bucket:
        return self.bucket_


def test_gcs_store_keeps_each_release_and_replaces_the_served_copy() -> None:
    client = _Client()
    store = GcsSiteFileStore(client, "site")
    store.publish("r1", [SiteFile("llms.txt", b"one", "text/plain")])
    store.publish("r1", [SiteFile("llms.txt", b"retry", "text/plain")])
    store.publish("r2", [SiteFile("llms.txt", b"two", "text/plain")])
    objects = client.bucket_.objects
    assert objects["r1/llms.txt"] == (b"one", "text/plain", CACHE_CONTROL)
    assert objects["r2/llms.txt"][0] == b"two"
    assert objects["current/llms.txt"] == (b"two", "text/plain", CACHE_CONTROL)


@dataclass
class _RecordingStore:
    published: list[tuple[str, list[str]]] = field(default_factory=list)

    def publish(self, release_id: str, files: Sequence[SiteFile]) -> None:
        self.published.append((release_id, [file.name for file in files]))


def _daily(site_files: _RecordingStore | None) -> DailyJob:
    return DailyJob(
        open_review=lambda: pytest.fail("not used"),
        open_releases=lambda: pytest.fail("not used"),
        run_cleanup=lambda: None,
        releases=MemorySnapshotStore(),
        dataset_repository=None,
        backup=None,
        telemetry=RecordingTelemetry().telemetry,
        clock=FakeClock(),
        site_files=site_files,
        site_links=LINKS,
    )


def test_daily_job_publishes_site_files_with_the_release() -> None:
    store = _RecordingStore()
    export = ReleaseExport(
        "2026.11.02.1", datetime(2026, 11, 2, 6, tzinfo=UTC), {}, render(RELEASE)
    )
    _daily(store)._distribute(export, DailyResult())
    assert store.published[0][0] == "2026.11.02.1"
    assert {"robots.txt", "llms.txt", "llms-full.txt", "docs/llms-full.txt", "sitemap.xml"} <= set(
        store.published[0][1]
    )
    # Unconfigured: skipped with a warning, the release still distributed.
    _daily(None)._distribute(export, DailyResult())


def test_cli_writes_site_files_into_a_folder(tmp_path: Path) -> None:
    release = tmp_path / "release.json"
    release.write_text(json.dumps(RELEASE), encoding="utf-8")
    result = CliRunner().invoke(
        app,
        [
            "site-files",
            "--release",
            str(release),
            "--out",
            str(tmp_path / "out"),
            "--site-url",
            "https://zeitwide.example",
            "--api-url",
            "https://api.zeitwide.example",
        ],
    )
    assert result.exit_code == 0, result.output
    assert (tmp_path / "out" / "sitemaps" / "GB-CHAPS.xml").is_file()
    assert (tmp_path / "out" / "docs" / "llms-full.txt").is_file()
    DirectorySiteFileStore(tmp_path / "again").publish(
        "r", [SiteFile("a/b.txt", b"x", "text/plain")]
    )
    assert (tmp_path / "again" / "a" / "b.txt").read_bytes() == b"x"


def _site(
    files: dict[str, str], closures: dict[str, int], broken: frozenset[str] = frozenset()
) -> httpx.MockTransport:
    def handle(request: httpx.Request) -> httpx.Response:
        url = str(request.url)
        path = request.url.path
        if url in broken:
            return httpx.Response(404)
        if request.url.host == "zeitwide.example" and path.lstrip("/") in files:
            return httpx.Response(200, text=files[path.lstrip("/")])
        if path == "/v1/calendars":
            return httpx.Response(
                200,
                json={
                    "calendars": [
                        {
                            "id": "GB-CHAPS",
                            "coverage": {"type": "years", "from": "2026-01-01", "to": "2027-12-31"},
                        },
                        {
                            "id": "EU-T2",
                            "coverage": {"type": "years", "from": "2026-01-01", "to": "2027-12-31"},
                        },
                        {"id": "US-FEDNOW", "coverage": {"type": "unlimited"}},
                    ]
                },
            )
        if path.endswith("/closures"):
            calendar = path.split("/")[3]
            year = request.url.params["from"][:4]
            count = closures[calendar] if year == "2026" else 0
            return httpx.Response(200, json={"closures": [{}] * count})
        return httpx.Response(200, text="page")

    return httpx.MockTransport(handle)


def test_validator_passes_a_good_site_and_names_each_problem() -> None:
    files = _files()
    with httpx.Client(transport=_site(files, {"GB-CHAPS": 3, "EU-T2": 2})) as client:
        assert (
            validate_site(client, "https://zeitwide.example", "https://api.zeitwide.example") == []
        )
    links = llms_links(files["llms.txt"])
    assert "https://zeitwide.example/pricing.md" in links
    # The API, MCP and A2A lines are endpoints, not pages: not checked. The server card is a
    # document that answers a GET, so it is a link like any other.
    assert [link for link in links if link.startswith("https://api.")] == [
        "https://api.zeitwide.example/v1/server-card"
    ]
    assert {
        "https://zeitwide.example/.well-known/ai-catalog.json",
        "https://zeitwide.example/.well-known/agent-card.json",
        "https://zeitwide.example/.well-known/zeitwide-keys.json",
        "https://zeitwide.example/openapi.json",
    } <= set(links)
    broken = frozenset({"https://zeitwide.example/pricing.md"})
    with httpx.Client(transport=_site(files, {"GB-CHAPS": 4, "EU-T2": 2}, broken)) as client:
        problems = validate_site(client, "https://zeitwide.example", "https://api.zeitwide.example")
    assert problems == [
        "https://zeitwide.example/pricing.md answered 404",
        "llms-full.txt lists 3 closures for GB-CHAPS; the API 4",
    ]
