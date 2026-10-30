import gzip
import json
from collections.abc import Iterator
from datetime import UTC, datetime
from pathlib import Path

import pytest
import respx
from tenacity import wait_none

from support.doubles import START, FakeClock
from support.goldens import goldens
from zeitwide_pipeline.dryrun import archive_capture_time, dry_run, save_fixture
from zeitwide_pipeline.fetch import Fetcher, HostThrottle, new_client
from zeitwide_pipeline.sources.catalog import govuk_bank_holidays

SOURCE = govuk_bank_holidays.SOURCE
GOLDEN = goldens({SOURCE.id})[-1]


@pytest.fixture
def router() -> Iterator[respx.MockRouter]:
    with respx.mock(assert_all_called=False) as mock:
        mock.get("https://www.gov.uk/robots.txt").respond(404)
        mock.get("https://web.archive.org/robots.txt").respond(404)
        yield mock


@pytest.fixture
def fetcher() -> Iterator[Fetcher]:
    with new_client() as client:
        yield Fetcher(
            client,
            "test",
            FakeClock(),
            throttle=HostThrottle(sleep=lambda _: None),
            retry_wait=wait_none(),
        )


def serve(router: respx.MockRouter, url: str = SOURCE.url) -> None:
    router.get(url).respond(
        200, content=GOLDEN.content, headers={"Content-Type": "application/json"}
    )


def test_dry_run_reports_observations_per_calendar(
    router: respx.MockRouter, fetcher: Fetcher
) -> None:
    serve(router)

    [result] = dry_run([SOURCE], fetcher, {"GB-EAW": object(), "GB-CHAPS": object()})  # type: ignore[dict-item]

    assert result.ok
    assert set(json.loads(result.to_json())["by_calendar"]) == {"GB-EAW", "GB-CHAPS"}


def test_dry_run_reports_calendars_missing_from_the_catalog(
    router: respx.MockRouter, fetcher: Fetcher
) -> None:
    serve(router)

    [result] = dry_run([SOURCE], fetcher, {})

    assert not result.ok
    assert result.error is not None
    assert "missing from the catalog" in result.error


def test_dry_run_reports_fetch_errors(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(SOURCE.url).respond(404)

    [result] = dry_run([SOURCE], fetcher, {})

    assert (result.ok, json.loads(result.to_json())["observations"]) == (False, 0)


def test_dry_run_rejects_304_to_unconditional_request(
    router: respx.MockRouter, fetcher: Fetcher
) -> None:
    router.get(SOURCE.url).respond(304)

    [result] = dry_run([SOURCE], fetcher, {})

    assert result.error is not None
    assert "304" in result.error


def test_save_fixture_stores_exact_bytes_and_metadata(
    router: respx.MockRouter, fetcher: Fetcher, tmp_path: Path
) -> None:
    serve(router)

    path = save_fixture(fetcher, SOURCE, "live", tmp_path, clock=FakeClock())

    assert gzip.decompress(path.read_bytes()) == GOLDEN.content
    meta = json.loads((tmp_path / SOURCE.id / "live.meta.json").read_text(encoding="utf-8"))
    assert (meta["url"], meta["fetched_at"]) == (SOURCE.url, START.isoformat())


def test_save_fixture_dates_an_archive_capture_by_its_timestamp(
    router: respx.MockRouter, fetcher: Fetcher, tmp_path: Path
) -> None:
    url = f"https://web.archive.org/web/20250315000000id_/{SOURCE.url}"
    serve(router, url)

    save_fixture(fetcher, SOURCE, "archive", tmp_path, clock=FakeClock(), url=url)

    meta = json.loads((tmp_path / SOURCE.id / "archive.meta.json").read_text(encoding="utf-8"))
    assert meta["fetched_at"] == "2025-03-15T00:00:00+00:00"


def test_archive_capture_time_ignores_other_urls() -> None:
    assert archive_capture_time(SOURCE.url) is None
    assert archive_capture_time(
        "https://web.archive.org/web/20240102030405id_/https://x.test/"
    ) == datetime(2024, 1, 2, 3, 4, 5, tzinfo=UTC)
