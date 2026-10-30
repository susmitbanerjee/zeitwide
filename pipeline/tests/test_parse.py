import json
from dataclasses import asdict
from datetime import UTC, datetime
from pathlib import Path

import pytest

from support.fixture_source import HolidayTableParser, holiday_page
from zeitwide_pipeline.models import Observation, SnapshotMeta
from zeitwide_pipeline.sources import ParseError, parse_snapshot

FIXTURES = Path(__file__).parent / "fixtures" / "fixture-holidays"
META = SnapshotMeta(
    source_id="fixture-holidays",
    url="https://fixtures.example.test/holidays",
    fetched_at=datetime(2026, 10, 28, tzinfo=UTC),
    content_type="text/html",
    sha256="0" * 64,
)
PARSER = HolidayTableParser(calendar_id="US-FEDWIRE")


def test_parser_matches_golden_observations() -> None:
    content = (FIXTURES / "2026.html").read_bytes()
    expected = json.loads((FIXTURES / "2026.expected.json").read_text(encoding="utf-8"))

    observations = parse_snapshot(PARSER, content, META)

    assert [
        {**asdict(observation), "entry_date": observation.entry_date.isoformat()}
        for observation in observations
    ] == expected


def test_empty_table_raises_parse_error() -> None:
    with pytest.raises(ParseError, match="no rows"):
        parse_snapshot(PARSER, holiday_page(), META)


def test_page_without_table_raises_parse_error() -> None:
    with pytest.raises(ParseError, match="No holidays table"):
        parse_snapshot(PARSER, b"<html><body><p>Moved</p></body></html>", META)


def test_changed_header_raises_parse_error() -> None:
    page = holiday_page().replace(b"<th>Status</th>", b"<th>Open?</th>")

    with pytest.raises(ParseError, match="Unexpected header"):
        parse_snapshot(PARSER, page, META)


@pytest.mark.parametrize(
    "row",
    [("2026-13-01", "Bad date", "Closed"), ("2026-11-26", "Thanksgiving", "Half day")],
)
def test_unexpected_row_raises_parse_error(row: tuple[str, str, str]) -> None:
    with pytest.raises(ParseError, match="Unexpected"):
        parse_snapshot(PARSER, holiday_page(row), META)


def test_non_utf8_page_raises_parse_error() -> None:
    with pytest.raises(ParseError, match="not UTF-8"):
        parse_snapshot(PARSER, b"\xff\xfe", META)


def test_snapshot_meta_rejects_naive_time() -> None:
    with pytest.raises(ValueError, match="timezone-aware"):
        SnapshotMeta("s", "https://x.test", datetime(2026, 1, 1), "text/html", "0" * 64)  # noqa: DTZ001


def test_snapshot_meta_rejects_short_hash() -> None:
    with pytest.raises(ValueError, match="64 hex"):
        SnapshotMeta("s", "https://x.test", datetime(2026, 1, 1, tzinfo=UTC), "text/html", "abc")


def test_observation_is_a_value() -> None:
    assert Observation("US-FEDWIRE", META.fetched_at.date(), False, "x") == Observation(
        "US-FEDWIRE", META.fetched_at.date(), False, "x"
    )
