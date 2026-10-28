from collections.abc import Iterator
from dataclasses import dataclass, replace

import pytest
import respx
from tenacity import wait_none

from settlecal_pipeline.fetch import (
    Fetched,
    Fetcher,
    FetchError,
    HostThrottle,
    Validators,
    new_client,
)
from settlecal_pipeline.retrieve import fetch_source
from settlecal_pipeline.sources import ParseError
from settlecal_pipeline.sources.catalog import bundesbank_holidays
from support.doubles import FakeClock

SOURCE = bundesbank_holidays.SOURCE
PDF = "https://www.bundesbank.de/resource/blob/764518/abc/x/feiertage-in-deutschland-1-data.pdf"


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


@pytest.fixture
def router() -> Iterator[respx.MockRouter]:
    with respx.mock(assert_all_called=False) as mock:
        mock.get("https://www.bundesbank.de/robots.txt").respond(404)
        yield mock


def test_follows_the_stable_page_to_the_current_document(
    router: respx.MockRouter, fetcher: Fetcher
) -> None:
    router.get(SOURCE.url).respond(
        200, html=f'<a href="{PDF.removeprefix("https://www.bundesbank.de")}">PDF</a>'
    )
    document = router.get(PDF).respond(
        200, content=b"%PDF", headers={"Content-Type": "application/pdf", "ETag": '"e1"'}
    )

    result = fetch_source(fetcher, SOURCE, Validators('"e0"'))

    assert isinstance(result, Fetched)
    assert (result.url, result.content) == (PDF, b"%PDF")
    assert document.calls.last.request.headers["If-None-Match"] == '"e0"'


def test_rejects_a_document_on_another_host(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(SOURCE.url).respond(
        200, html='<a href="https://elsewhere.example/resource/blob/764518/abc/x.pdf">PDF</a>'
    )

    with pytest.raises(ParseError, match="one holiday PDF link"):
        fetch_source(fetcher, SOURCE, Validators())


def test_rejects_a_page_that_is_not_modified(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(SOURCE.url).respond(304)

    with pytest.raises(FetchError, match="304"):
        fetch_source(fetcher, SOURCE, Validators())


@dataclass(frozen=True)
class ElsewhereLink:
    page_types: frozenset[str] = frozenset({"text/html"})

    def find(self, page: bytes, page_url: str) -> str:
        return "https://elsewhere.example/holidays.pdf"


def test_fetch_source_refuses_a_link_off_the_page_host(
    router: respx.MockRouter, fetcher: Fetcher
) -> None:
    router.get(SOURCE.url).respond(200, html="<p>page</p>")
    source = replace(SOURCE, document=ElsewhereLink())

    with pytest.raises(FetchError, match="another host"):
        fetch_source(fetcher, source, Validators())
