"""The site files written with each dataset release, for crawlers and AI agents.

robots.txt, llms.txt, llms-full.txt, docs/llms-full.txt and the sitemaps are pure
functions of the release document, the public docs and the OpenAPI document (both synced
into this package by ``make sync-docs``). The same release always gives the same bytes, so
the files change only when the data does.
"""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from typing import Protocol

from zeitwide_pipeline.site.api_reference import api_reference
from zeitwide_pipeline.site.docs import Doc, load_docs, load_openapi
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.llms import Discovery, docs_full_txt, llms_full_txt, llms_txt
from zeitwide_pipeline.site.release_view import read_release
from zeitwide_pipeline.site.robots import RobotsConfig, load_robots_config, robots_txt
from zeitwide_pipeline.site.sitemaps import build_sitemaps, render_index, render_sitemap

TEXT = "text/plain; charset=utf-8"
XML = "application/xml; charset=utf-8"
# Every cache may keep them an hour; a new release replaces them.
CACHE_CONTROL = "public, max-age=3600"

__all__ = ["CACHE_CONTROL", "SiteFile", "SiteFileStore", "SiteLinks", "build_site_files"]


@dataclass(frozen=True, slots=True)
class SiteFile:
    name: str
    content: bytes
    content_type: str


class SiteFileStore(Protocol):
    def publish(self, release_id: str, files: Sequence[SiteFile]) -> None:
        """Keeps the release's own copy and makes these the files the site serves."""
        ...


def build_site_files(
    release_document: Mapping[str, object],
    links: SiteLinks,
    *,
    docs: Sequence[Doc] | None = None,
    openapi: Mapping[str, object] | None = None,
    robots: RobotsConfig | None = None,
) -> list[SiteFile]:
    release = read_release(release_document)
    docs = load_docs() if docs is None else docs
    reference = api_reference(load_openapi() if openapi is None else openapi)
    robots = load_robots_config() if robots is None else robots
    sitemaps = build_sitemaps(release, docs)
    discovery = Discovery(
        sitemap_urls=sum(len(sitemap.urls) for sitemap in sitemaps),
        sitemap_files=len(sitemaps),
        named_agents=robots.named_agents,
    )
    llms = llms_txt(release, docs, links, discovery)
    files = [
        SiteFile("robots.txt", robots_txt(robots, links).encode(), TEXT),
        SiteFile("llms.txt", llms.encode(), TEXT),
        SiteFile("llms-full.txt", llms_full_txt(release, docs, reference, links).encode(), TEXT),
        SiteFile(
            "docs/llms-full.txt", docs_full_txt(release, docs, reference, links).encode(), TEXT
        ),
        SiteFile("sitemap.xml", render_index(sitemaps, links).encode(), XML),
    ]
    files += [
        SiteFile(sitemap.name, render_sitemap(sitemap, links).encode(), XML) for sitemap in sitemaps
    ]
    return files
