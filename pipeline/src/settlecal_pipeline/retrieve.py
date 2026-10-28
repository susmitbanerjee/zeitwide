"""Fetching a source's document, following its stable page when it has one."""

from urllib.parse import urlsplit

from settlecal_pipeline.fetch import Fetcher, FetchError, FetchResult, Unchanged, Validators
from settlecal_pipeline.sources import Source


def fetch_source(fetcher: Fetcher, source: Source, validators: Validators) -> FetchResult:
    """The document to parse. For a linked document, ``validators`` apply to the document.

    The page is always fetched in full (it is small and only points the way), and the link
    it gives must stay on the page's host, over https.
    """
    if source.document is None:
        return fetcher.fetch(source.url, source.content_types, validators)
    page = fetcher.fetch(source.url, source.document.page_types, Validators())
    if isinstance(page, Unchanged):
        raise FetchError(f"{source.id} answered 304 to an unconditional request")
    document_url = source.document.find(page.content, page.url or source.url)
    if urlsplit(document_url).netloc != urlsplit(page.url or source.url).netloc:
        raise FetchError(f"{source.id} links to a document on another host: {document_url}")
    return fetcher.fetch(document_url, source.content_types, validators)
