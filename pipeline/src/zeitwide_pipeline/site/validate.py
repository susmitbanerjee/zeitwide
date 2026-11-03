"""Checks a live site's agent files against the rules and against the API.

- llms.txt: one H1, a blockquote summary, the H2 sections in order, every bullet a link
  with a description, every link answering 200, and under 10 KB.
- llms-full.txt: each calendar's table has as many rows as the API lists closures.
- sitemap.xml and every sitemap it lists: valid against the sitemaps.org 0.9 schemas.
"""

import re
from collections.abc import Callable, Iterable
from datetime import date
from functools import cache
from importlib import resources
from urllib.parse import urljoin

import httpx
import xmlschema

from zeitwide_pipeline.site.llms import CLOSURE_TABLE_HEADER, MAX_LLMS_TXT_BYTES

SECTIONS = ("Answers", "Calendars", "Compare", "Docs", "Discovery", "Optional")
_BULLET = re.compile(r"^- \[(?P<title>[^\]]+)\]\((?P<url>[^)\s]+)\)(?P<rest>.*)$")
_CALENDAR_HEADING = re.compile(r"^## .+ \((?P<id>[A-Z0-9-]+)\)$")
_YEARS_PER_REQUEST = 1


def check_llms_txt(text: str) -> list[str]:
    """Structure problems in an llms.txt, or none."""
    problems: list[str] = []
    lines = text.splitlines()
    size = len(text.encode())
    if size > MAX_LLMS_TXT_BYTES:
        problems.append(f"llms.txt is {size} bytes, over {MAX_LLMS_TXT_BYTES}")
    h1 = [line for line in lines if line.startswith("# ")]
    if len(h1) != 1 or not lines or lines[0] != "# Zeitwide":
        problems.append("llms.txt must start with exactly one H1, '# Zeitwide'")
    first_text = next((line for line in lines[1:] if line.strip()), "")
    if not first_text.startswith("> "):
        problems.append("the line after the H1 must be the blockquote summary")
    sections = [line[3:] for line in lines if line.startswith("## ")]
    if tuple(sections) != SECTIONS:
        problems.append(f"H2 sections are {sections}, expected {list(SECTIONS)}")
    for line in lines:
        if not line.startswith("- "):
            continue
        match = _BULLET.match(line)
        if match is None:
            problems.append(f"bullet is not a link: {line}")
        elif not re.match(r"^: \S", match["rest"]):
            problems.append(f"link has no description: {match['url']}")
    return problems


def llms_links(text: str) -> list[str]:
    """The URL of every link bullet in an llms.txt, in order, once each.

    The lines naming the API and MCP endpoints are not pages: an API base path or an MCP
    server need not answer a plain GET, so only the bullets' links must answer 200.
    """
    urls = [
        match["url"] for line in text.splitlines() if (match := _BULLET.match(line)) is not None
    ]
    return list(dict.fromkeys(urls))


def closure_rows(full_text: str) -> dict[str, int]:
    """Rows in each calendar's closure table in llms-full.txt, by calendar ID."""
    counts: dict[str, int] = {}
    current: str | None = None
    in_table = False
    for line in full_text.splitlines():
        heading = _CALENDAR_HEADING.match(line)
        if heading is not None:
            current, in_table = heading["id"], False
            counts[current] = 0
        elif current is not None and line == CLOSURE_TABLE_HEADER:
            in_table = True
        elif in_table and current is not None and line.startswith("| ") and "| ---" not in line:
            counts[current] += 1
        elif in_table and not line.strip():
            in_table = False
    return counts


@cache
def _schema(name: str) -> xmlschema.XMLSchema:
    source = resources.files(__package__).joinpath("schemas", name).read_text(encoding="utf-8")
    return xmlschema.XMLSchema(source)


def check_sitemap(xml: str, *, index: bool) -> list[str]:
    """Schema errors in a sitemap (or sitemap index), or none. Entities are never expanded."""
    schema = _schema("siteindex.xsd" if index else "sitemap.xsd")
    return [str(error.reason) for error in schema.iter_errors(xml)][:5]


def sitemap_locations(xml: str) -> list[str]:
    return re.findall(r"<loc>([^<]+)</loc>", xml)


def validate_site(
    client: httpx.Client,
    site_url: str,
    api_url: str,
    *,
    log: Callable[[str], None] = lambda _message: None,
) -> list[str]:
    """Every problem found on the live site, or none."""
    problems: list[str] = []
    llms = _get(client, urljoin(site_url, "/llms.txt"), problems)
    if llms is not None:
        problems += check_llms_txt(llms)
        links = llms_links(llms)
        log(f"checking {len(links)} llms.txt links")
        problems += [
            f"{url} answered {status}"
            for url, status in _statuses(client, links)
            if status != httpx.codes.OK
        ]
    full = _get(client, urljoin(site_url, "/llms-full.txt"), problems)
    if full is not None:
        problems += _check_counts(client, api_url, closure_rows(full))
    index = _get(client, urljoin(site_url, "/sitemap.xml"), problems)
    if index is not None:
        problems += [f"sitemap.xml: {error}" for error in check_sitemap(index, index=True)]
        for location in sitemap_locations(index):
            sitemap = _get(client, location, problems)
            if sitemap is not None:
                problems += [f"{location}: {e}" for e in check_sitemap(sitemap, index=False)]
    return problems


def _check_counts(client: httpx.Client, api_url: str, rows: dict[str, int]) -> list[str]:
    response = client.get(urljoin(api_url, "/v1/calendars"))
    response.raise_for_status()
    problems = []
    for calendar in response.json()["calendars"]:
        expected = _api_closures(client, api_url, calendar)
        found = rows.get(calendar["id"])
        if found != expected:
            problems.append(
                f"llms-full.txt lists {found} closures for {calendar['id']}; the API {expected}"
            )
    return problems


def _api_closures(client: httpx.Client, api_url: str, calendar: dict[str, object]) -> int:
    coverage = calendar["coverage"]
    if not isinstance(coverage, dict) or coverage.get("type") != "years":
        return 0
    first = date.fromisoformat(str(coverage["from"])).year
    last = date.fromisoformat(str(coverage["to"])).year
    total = 0
    # The API takes at most 731 days per request: one year at a time.
    for year in range(first, last + 1, _YEARS_PER_REQUEST):
        response = client.get(
            urljoin(api_url, f"/v1/calendars/{calendar['id']}/closures"),
            params={"from": f"{year}-01-01", "to": f"{year}-12-31"},
        )
        response.raise_for_status()
        total += len(response.json()["closures"])
    return total


def _get(client: httpx.Client, url: str, problems: list[str]) -> str | None:
    response = client.get(url)
    if response.status_code != httpx.codes.OK:
        problems.append(f"{url} answered {response.status_code}")
        return None
    return response.text


def _statuses(client: httpx.Client, urls: Iterable[str]) -> list[tuple[str, int]]:
    return [(url, client.get(url).status_code) for url in urls]
