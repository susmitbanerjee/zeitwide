"""HTTP fetching that is polite to official sources and fails loudly.

Polite: an honest User-Agent, robots.txt, at most one request per second per host, and
conditional requests. Loud: anything other than a clean 200 or 304 raises FetchError.
"""

import time
from collections.abc import Callable, Iterable
from dataclasses import dataclass
from datetime import datetime
from urllib.parse import urljoin, urlsplit
from urllib.robotparser import RobotFileParser

import httpx
from tenacity import Retrying, retry_if_exception_type, stop_after_attempt, wait_exponential_jitter
from tenacity.wait import WaitBaseT

from zeitwide_pipeline.clock import Clock

BOT_NAME = "ZeitwideBot"
CONNECT_TIMEOUT_S = 5.0
READ_TIMEOUT_S = 30.0
TIMEOUT = httpx.Timeout(READ_TIMEOUT_S, connect=CONNECT_TIMEOUT_S)
MAX_BYTES = 10 * 1024 * 1024
MAX_ROBOTS_BYTES = 512 * 1024
MAX_REDIRECTS = 3
MAX_ATTEMPTS = 3
MIN_INTERVAL_PER_HOST_S = 1.0
RETRY_WAIT_INITIAL_S = 1.0
RETRY_WAIT_MAX_S = 10.0

HTTP_OK = 200
HTTP_NOT_MODIFIED = 304
HTTP_TOO_MANY_REQUESTS = 429
HTTP_CLIENT_ERROR = 400
HTTP_SERVER_ERROR = 500
REDIRECT_STATUSES = frozenset({301, 302, 303, 307, 308})


class FetchError(Exception):
    """The source couldn't be fetched, and trying again right away won't help."""


class TransientFetchError(FetchError):
    """A timeout, network error, 429 or 5xx: worth retrying."""


@dataclass(frozen=True, slots=True)
class Validators:
    """Saved from the last fully processed response, for a conditional request."""

    etag: str | None = None
    last_modified: str | None = None


@dataclass(frozen=True, slots=True)
class Unchanged:
    """The server answered 304 Not Modified."""


@dataclass(frozen=True, slots=True)
class Fetched:
    content: bytes
    content_type: str
    http_status: int
    validators: Validators
    fetched_at: datetime
    # Where the bytes came from, after any redirects.
    url: str = ""


type FetchResult = Unchanged | Fetched


def user_agent(version: str, contact_url: str) -> str:
    return f"{BOT_NAME}/{version} (+{contact_url})"


def new_client() -> httpx.Client:
    """Redirects are followed by hand, so each target can be checked."""
    return httpx.Client(timeout=TIMEOUT, follow_redirects=False)


class HostThrottle:
    """Spaces requests to the same host at least ``min_interval_s`` apart."""

    def __init__(
        self,
        min_interval_s: float = MIN_INTERVAL_PER_HOST_S,
        monotonic: Callable[[], float] = time.monotonic,
        sleep: Callable[[float], None] = time.sleep,
    ) -> None:
        self._min_interval_s = min_interval_s
        self._monotonic = monotonic
        self._sleep = sleep
        self._last_request: dict[str, float] = {}
        self._host_interval_s: dict[str, float] = {}

    def require_interval(self, host: str, seconds: float) -> None:
        """Slows one host further, for a robots.txt ``Crawl-delay``. Never speeds it up."""
        self._host_interval_s[host] = max(seconds, self._min_interval_s)

    def wait(self, host: str) -> None:
        last = self._last_request.get(host)
        if last is not None:
            interval = self._host_interval_s.get(host, self._min_interval_s)
            remaining = interval - (self._monotonic() - last)
            if remaining > 0:
                self._sleep(remaining)
        self._last_request[host] = self._monotonic()


class Fetcher:
    def __init__(  # noqa: PLR0913 - all collaborators are injected for tests
        self,
        client: httpx.Client,
        user_agent: str,
        clock: Clock,
        *,
        throttle: HostThrottle | None = None,
        retry_wait: WaitBaseT | None = None,
        max_bytes: int = MAX_BYTES,
    ) -> None:
        self._client = client
        self._user_agent = user_agent
        self._clock = clock
        self._throttle = throttle or HostThrottle()
        self._retry_wait = retry_wait or wait_exponential_jitter(
            initial=RETRY_WAIT_INITIAL_S, max=RETRY_WAIT_MAX_S
        )
        self._max_bytes = max_bytes
        self._robots: dict[str, RobotFileParser] = {}

    def fetch(self, url: str, accepted_types: Iterable[str], validators: Validators) -> FetchResult:
        """Fetches ``url``, retrying transient failures with backoff and jitter."""
        _require_https(url)
        accepted = frozenset(accepted_types)
        retrying = Retrying(
            retry=retry_if_exception_type(TransientFetchError),
            stop=stop_after_attempt(MAX_ATTEMPTS),
            wait=self._retry_wait,
            reraise=True,
        )
        for attempt in retrying:
            with attempt:
                self._check_robots(url)
                return self._fetch_once(url, accepted, validators)
        raise AssertionError("unreachable: tenacity re-raises the last error")  # pragma: no cover

    def _fetch_once(
        self, url: str, accepted: frozenset[str], validators: Validators
    ) -> FetchResult:
        headers = self._headers(accepted, validators)
        current = url
        for _ in range(MAX_REDIRECTS + 1):
            self._throttle.wait(urlsplit(current).netloc)
            try:
                with self._client.stream("GET", current, headers=headers) as response:
                    if response.status_code in REDIRECT_STATUSES:
                        current = _redirect_target(url, current, response)
                        continue
                    return self._read(response, accepted)
            except httpx.TransportError as error:
                raise TransientFetchError(f"{type(error).__name__} fetching {current}") from error
        raise FetchError(f"More than {MAX_REDIRECTS} redirects from {url}")

    def _headers(self, accepted: frozenset[str], validators: Validators) -> dict[str, str]:
        headers = {"User-Agent": self._user_agent, "Accept": ", ".join(sorted(accepted))}
        if validators.etag:
            headers["If-None-Match"] = validators.etag
        if validators.last_modified:
            headers["If-Modified-Since"] = validators.last_modified
        return headers

    def _read(self, response: httpx.Response, accepted: frozenset[str]) -> FetchResult:
        status = response.status_code
        if status == HTTP_NOT_MODIFIED:
            return Unchanged()
        _raise_for_status(response)
        content_type = _media_type(response)
        if content_type not in accepted:
            raise FetchError(f"Unexpected content type {content_type!r} from {response.url}")
        return Fetched(
            content=_read_capped(response, self._max_bytes),
            content_type=content_type,
            http_status=status,
            validators=Validators(
                etag=response.headers.get("ETag"),
                last_modified=response.headers.get("Last-Modified"),
            ),
            fetched_at=self._clock(),
            url=str(response.url),
        )

    def _check_robots(self, url: str) -> None:
        parts = urlsplit(url)
        origin = f"{parts.scheme}://{parts.netloc}"
        robots = self._robots.get(origin)
        if robots is None:
            robots = self._load_robots(origin)
            self._robots[origin] = robots
            delay = robots.crawl_delay(BOT_NAME)
            if delay is not None:
                self._throttle.require_interval(parts.netloc, float(delay))
        if not robots.can_fetch(BOT_NAME, url):
            raise FetchError(f"robots.txt at {origin} disallows {url}")

    def _load_robots(self, origin: str) -> RobotFileParser:
        robots = RobotFileParser()
        self._throttle.wait(urlsplit(origin).netloc)
        try:
            with self._client.stream(
                "GET",
                f"{origin}/robots.txt",
                headers={"User-Agent": self._user_agent},
                follow_redirects=True,
            ) as response:
                # RFC 9309: a missing robots.txt allows everything; an unreachable one
                # means "don't crawl", so it's retried like any transient failure.
                if HTTP_CLIENT_ERROR <= response.status_code < HTTP_SERVER_ERROR:
                    robots.parse([])
                    return robots
                _raise_for_status(response)
                body = _read_capped(response, MAX_ROBOTS_BYTES)
        except httpx.TransportError as error:
            raise TransientFetchError(f"{type(error).__name__} fetching robots.txt") from error
        robots.parse(body.decode("utf-8", errors="replace").splitlines())
        return robots


def _require_https(url: str) -> None:
    if urlsplit(url).scheme != "https":
        raise FetchError(f"Only https sources are fetched, not {url}")


def _redirect_target(original: str, current: str, response: httpx.Response) -> str:
    location = str(response.headers.get("Location", ""))
    if not location:
        raise FetchError(f"Redirect without a Location from {current}")
    target = urljoin(current, location)
    _require_https(target)
    if urlsplit(target).netloc != urlsplit(original).netloc:
        raise FetchError(f"Redirect from {original} leaves its host: {target}")
    return target


def _raise_for_status(response: httpx.Response) -> None:
    status = response.status_code
    if status == HTTP_TOO_MANY_REQUESTS or status >= HTTP_SERVER_ERROR:
        raise TransientFetchError(f"HTTP {status} from {response.url}")
    if status != HTTP_OK:
        raise FetchError(f"HTTP {status} from {response.url}")


def _media_type(response: httpx.Response) -> str:
    return str(response.headers.get("Content-Type", "")).split(";")[0].strip().lower()


def _read_capped(response: httpx.Response, max_bytes: int) -> bytes:
    declared = response.headers.get("Content-Length")
    if declared is not None and declared.isdigit() and int(declared) > max_bytes:
        raise FetchError(f"{response.url} is larger than {max_bytes} bytes")
    chunks: list[bytes] = []
    size = 0
    for chunk in response.iter_bytes():
        size += len(chunk)
        if size > max_bytes:
            raise FetchError(f"{response.url} is larger than {max_bytes} bytes")
        chunks.append(chunk)
    return b"".join(chunks)
