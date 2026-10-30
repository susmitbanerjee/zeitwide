from collections.abc import Iterator

import httpx
import pytest
import respx
from tenacity import wait_none

from support.doubles import START
from zeitwide_pipeline.fetch import (
    MAX_ATTEMPTS,
    Fetched,
    Fetcher,
    FetchError,
    HostThrottle,
    TransientFetchError,
    Unchanged,
    Validators,
    new_client,
    user_agent,
)

URL = "https://fixtures.example.test/holidays"
ROBOTS = "https://fixtures.example.test/robots.txt"
HTML = frozenset({"text/html"})
UA = user_agent("1.2.3", "https://zeitwide.example/bot")


class NoSleep:
    def __init__(self) -> None:
        self.slept: list[float] = []

    def __call__(self, seconds: float) -> None:
        self.slept.append(seconds)


@pytest.fixture
def router() -> Iterator[respx.MockRouter]:
    with respx.mock(assert_all_called=False) as mock:
        mock.get(ROBOTS).respond(404)
        yield mock


@pytest.fixture
def fetcher() -> Iterator[Fetcher]:
    with new_client() as client:
        yield Fetcher(
            client,
            UA,
            lambda: START,
            throttle=HostThrottle(sleep=NoSleep()),
            retry_wait=wait_none(),
            max_bytes=1024,
        )


def html(body: bytes = b"<p>ok</p>", headers: dict[str, str] | None = None) -> httpx.Response:
    return httpx.Response(
        200, content=body, headers={"Content-Type": "text/html; charset=utf-8", **(headers or {})}
    )


def test_fetch_returns_content_and_validators(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(URL).mock(
        return_value=html(headers={"ETag": '"v1"', "Last-Modified": "Wed, 01 Oct 2026"})
    )

    result = fetcher.fetch(URL, HTML, Validators())

    assert result == Fetched(
        content=b"<p>ok</p>",
        content_type="text/html",
        http_status=200,
        validators=Validators('"v1"', "Wed, 01 Oct 2026"),
        fetched_at=START,
        url=URL,
    )


def test_fetch_sends_honest_user_agent(router: respx.MockRouter, fetcher: Fetcher) -> None:
    route = router.get(URL).mock(return_value=html())

    fetcher.fetch(URL, HTML, Validators())

    assert route.calls.last.request.headers["User-Agent"] == (
        "ZeitwideBot/1.2.3 (+https://zeitwide.example/bot)"
    )


def test_fetch_sends_conditional_headers(router: respx.MockRouter, fetcher: Fetcher) -> None:
    route = router.get(URL).respond(304)

    result = fetcher.fetch(URL, HTML, Validators('"v1"', "Wed, 01 Oct 2026"))

    request = route.calls.last.request
    assert (request.headers["If-None-Match"], request.headers["If-Modified-Since"]) == (
        '"v1"',
        "Wed, 01 Oct 2026",
    )
    assert result == Unchanged()


def test_fetch_retries_server_errors(router: respx.MockRouter, fetcher: Fetcher) -> None:
    route = router.get(URL).mock(side_effect=[httpx.Response(503), httpx.Response(502), html()])

    result = fetcher.fetch(URL, HTML, Validators())

    assert isinstance(result, Fetched)
    assert route.call_count == 3


def test_fetch_gives_up_after_max_attempts(router: respx.MockRouter, fetcher: Fetcher) -> None:
    route = router.get(URL).respond(500)

    with pytest.raises(TransientFetchError, match="HTTP 500"):
        fetcher.fetch(URL, HTML, Validators())
    assert route.call_count == MAX_ATTEMPTS


def test_fetch_retries_timeouts(router: respx.MockRouter, fetcher: Fetcher) -> None:
    route = router.get(URL).mock(side_effect=[httpx.ConnectTimeout("slow"), html()])

    fetcher.fetch(URL, HTML, Validators())

    assert route.call_count == 2


def test_fetch_does_not_retry_client_errors(router: respx.MockRouter, fetcher: Fetcher) -> None:
    route = router.get(URL).respond(404)

    with pytest.raises(FetchError, match="HTTP 404") as error:
        fetcher.fetch(URL, HTML, Validators())
    assert not isinstance(error.value, TransientFetchError)
    assert route.call_count == 1


def test_fetch_rejects_unexpected_content_type(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(URL).respond(200, content=b"%PDF", headers={"Content-Type": "application/pdf"})

    with pytest.raises(FetchError, match="content type"):
        fetcher.fetch(URL, HTML, Validators())


def test_fetch_rejects_declared_oversize_body(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(URL).mock(return_value=html(headers={"Content-Length": "4096"}))

    with pytest.raises(FetchError, match="larger than"):
        fetcher.fetch(URL, HTML, Validators())


def test_fetch_rejects_streamed_oversize_body(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(URL).mock(
        return_value=httpx.Response(
            200, headers={"Content-Type": "text/html"}, stream=httpx.ByteStream(b"x" * 2048)
        )
    )

    with pytest.raises(FetchError, match="larger than"):
        fetcher.fetch(URL, HTML, Validators())


def test_fetch_follows_same_host_redirect(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(URL).respond(301, headers={"Location": "/holidays/2026"})
    router.get(f"{URL}/2026").mock(return_value=html(b"moved"))

    result = fetcher.fetch(URL, HTML, Validators())

    assert isinstance(result, Fetched)
    assert result.content == b"moved"


@pytest.mark.parametrize(
    ("location", "message"),
    [
        ("https://elsewhere.example.test/x", "leaves its host"),
        ("http://fixtures.example.test/x", "https"),
    ],
)
def test_fetch_rejects_unsafe_redirect(
    router: respx.MockRouter, fetcher: Fetcher, location: str, message: str
) -> None:
    router.get(URL).respond(302, headers={"Location": location})

    with pytest.raises(FetchError, match=message):
        fetcher.fetch(URL, HTML, Validators())


def test_fetch_rejects_redirect_without_location(
    router: respx.MockRouter, fetcher: Fetcher
) -> None:
    router.get(URL).respond(302)

    with pytest.raises(FetchError, match="without a Location"):
        fetcher.fetch(URL, HTML, Validators())


def test_fetch_stops_redirect_loops(router: respx.MockRouter, fetcher: Fetcher) -> None:
    router.get(URL).respond(302, headers={"Location": URL})

    with pytest.raises(FetchError, match="redirects"):
        fetcher.fetch(URL, HTML, Validators())


def test_fetch_rejects_plain_http_source(fetcher: Fetcher) -> None:
    with pytest.raises(FetchError, match="Only https"):
        fetcher.fetch("http://fixtures.example.test/holidays", HTML, Validators())


def test_fetch_respects_robots_disallow(fetcher: Fetcher) -> None:
    with respx.mock(assert_all_called=False) as mock:
        mock.get(ROBOTS).respond(200, text="User-agent: ZeitwideBot\nDisallow: /holidays\n")
        page = mock.get(URL).mock(return_value=html())

        with pytest.raises(FetchError, match=r"robots\.txt"):
            fetcher.fetch(URL, HTML, Validators())
        assert not page.called


def test_fetch_allows_paths_robots_permits(fetcher: Fetcher) -> None:
    with respx.mock(assert_all_called=False) as mock:
        robots = mock.get(ROBOTS).respond(200, text="User-agent: *\nDisallow: /private\n")
        mock.get(URL).mock(return_value=html())

        fetcher.fetch(URL, HTML, Validators())
        fetcher.fetch(URL, HTML, Validators())

        assert robots.call_count == 1


def test_fetch_treats_unreachable_robots_as_transient(fetcher: Fetcher) -> None:
    with respx.mock(assert_all_called=False) as mock:
        mock.get(ROBOTS).mock(side_effect=httpx.ConnectError("down"))

        with pytest.raises(TransientFetchError, match="robots"):
            fetcher.fetch(URL, HTML, Validators())


def test_throttle_spaces_requests_to_same_host() -> None:
    sleep = NoSleep()
    ticks = iter([0.0, 0.25, 0.25])
    throttle = HostThrottle(min_interval_s=1.0, monotonic=lambda: next(ticks), sleep=sleep)

    throttle.wait("a.test")
    throttle.wait("a.test")

    assert sleep.slept == [0.75]


def test_throttle_does_not_delay_other_hosts() -> None:
    sleep = NoSleep()
    throttle = HostThrottle(min_interval_s=1.0, monotonic=lambda: 0.0, sleep=sleep)

    throttle.wait("a.test")
    throttle.wait("b.test")

    assert sleep.slept == []


def test_fetch_honours_robots_crawl_delay() -> None:
    sleep = NoSleep()
    with new_client() as client:
        slow = Fetcher(
            client,
            UA,
            lambda: START,
            throttle=HostThrottle(monotonic=lambda: 0.0, sleep=sleep),
            retry_wait=wait_none(),
        )
        with respx.mock(assert_all_called=False) as mock:
            mock.get(ROBOTS).respond(200, text="User-agent: *\nCrawl-delay: 5\n")
            mock.get(URL).mock(return_value=html())

            slow.fetch(URL, HTML, Validators())

    assert sleep.slept == [5.0]
