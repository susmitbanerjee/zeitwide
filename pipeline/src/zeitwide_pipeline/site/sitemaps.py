"""sitemap.xml: an index, a sitemap of general pages, and one sitemap per calendar.

``lastmod`` is when the data behind a page last changed (the calendar's latest recorded
version), never the build time, so the files change only when the data does.
"""

from collections.abc import Sequence
from dataclasses import dataclass
from datetime import date, timedelta
from xml.sax.saxutils import escape

from zeitwide_pipeline.site.docs import Doc
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.paths import (
    answer_path,
    calendar_path,
    compare_pairs,
    compare_path,
    year_path,
)
from zeitwide_pipeline.site.release_view import CalendarView, ReleaseView

SITEMAP_NAMESPACE = "http://www.sitemaps.org/schemas/sitemap/0.9"
MAX_URLS_PER_SITEMAP = 50_000
PAGES_SITEMAP = "sitemaps/pages.xml"
# Answer pages listed after the release's date, as the web app prebuilds them.
UPCOMING_DAYS = 90


@dataclass(frozen=True, slots=True)
class SitemapUrl:
    path: str
    lastmod: date | None


@dataclass(frozen=True, slots=True)
class Sitemap:
    name: str
    urls: tuple[SitemapUrl, ...]

    @property
    def lastmod(self) -> date | None:
        dates = [url.lastmod for url in self.urls if url.lastmod is not None]
        return max(dates, default=None)


def calendar_sitemap_name(calendar_id: str) -> str:
    return f"sitemaps/{calendar_id}.xml"


def build_sitemaps(release: ReleaseView, docs: Sequence[Doc]) -> list[Sitemap]:
    """The pages sitemap, then one per calendar, in the release's calendar order."""
    sitemaps = [_pages(release, docs)]
    sitemaps += [_calendar(release, calendar) for calendar in release.calendars]
    for sitemap in sitemaps:
        if len(sitemap.urls) > MAX_URLS_PER_SITEMAP:
            raise ValueError(f"{sitemap.name} has {len(sitemap.urls)} URLs")
    return sitemaps


def _revised(calendar: CalendarView) -> date | None:
    return None if calendar.revised_at is None else calendar.revised_at.date()


def _latest(*dates: date | None) -> date | None:
    known = [day for day in dates if day is not None]
    return max(known, default=None)


def _pages(release: ReleaseView, docs: Sequence[Doc]) -> Sitemap:
    everything = _latest(*(_revised(calendar) for calendar in release.calendars))
    urls = [
        SitemapUrl("/", everything),
        SitemapUrl("/calendars", everything),
        SitemapUrl("/sources", everything),
        SitemapUrl("/pricing", None),
        SitemapUrl("/docs/api", None),
        *(SitemapUrl(doc.path, date.fromisoformat(doc.updated)) for doc in docs),
    ]
    for year in release.build_years:
        urls += [
            SitemapUrl(compare_path(a.id, b.id, year), _latest(_revised(a), _revised(b)))
            for a, b in compare_pairs(release, year)
        ]
    return Sitemap(PAGES_SITEMAP, tuple(urls))


def _calendar(release: ReleaseView, calendar: CalendarView) -> Sitemap:
    """The calendar page, its year pages, and the answer pages worth crawling: every closure
    it covers, plus the release's date and the 90 days after it (the pages the web app
    prebuilds). Other dates are answered on request but not listed."""
    revised = _revised(calendar)
    years = calendar.covered_years(release.build_years)
    urls = [SitemapUrl(calendar_path(calendar.id), revised)]
    urls += [SitemapUrl(year_path(calendar.id, year), revised) for year in years]
    upcoming = (release.today + timedelta(days=offset) for offset in range(UPCOMING_DAYS + 1))
    days = {closure.date for closure in calendar.closures} | {
        day for day in upcoming if day.year in years
    }
    urls += [SitemapUrl(answer_path(calendar.id, day), revised) for day in sorted(days)]
    return Sitemap(calendar_sitemap_name(calendar.id), tuple(urls))


def render_sitemap(sitemap: Sitemap, links: SiteLinks) -> str:
    entries = [
        f"<url><loc>{escape(links.page(url.path))}</loc>"
        + ("" if url.lastmod is None else f"<lastmod>{url.lastmod.isoformat()}</lastmod>")
        + "</url>"
        for url in sitemap.urls
    ]
    return _document("urlset", entries)


def render_index(sitemaps: Sequence[Sitemap], links: SiteLinks) -> str:
    entries = [
        f"<sitemap><loc>{escape(links.page('/' + sitemap.name))}</loc>"
        + ("" if sitemap.lastmod is None else f"<lastmod>{sitemap.lastmod.isoformat()}</lastmod>")
        + "</sitemap>"
        for sitemap in sitemaps
    ]
    return _document("sitemapindex", entries)


def _document(root: str, entries: Sequence[str]) -> str:
    body = "\n".join(entries)
    return (
        '<?xml version="1.0" encoding="UTF-8"?>\n'
        f'<{root} xmlns="{SITEMAP_NAMESPACE}">\n{body}\n</{root}>\n'
    )
