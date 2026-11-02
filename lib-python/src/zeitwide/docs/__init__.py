"""Zeitwide's docs, offline: the same Markdown pages as https://zeitwide.com/docs.

>>> from zeitwide import docs
>>> docs.get("rules").title
'How Zeitwide decides a business day'
>>> [doc.slug for doc in docs.all_docs()][0]
'index'
"""

import re
from dataclasses import dataclass
from importlib import resources

__all__ = ["Doc", "all_docs", "get"]

_FRONT_MATTER = re.compile(r"\A---\n(?P<fields>.*?)\n---\n", re.DOTALL)
_SITE = "https://zeitwide.com"


@dataclass(frozen=True, slots=True)
class Doc:
    """One page of the docs.

    Attributes:
        slug: ``index`` for the quickstart, otherwise the page's name.
        title: The page's title.
        description: One sentence on what the page covers.
        updated: The ISO date the page last changed.
        body: The page as Markdown, without its front matter.
    """

    slug: str
    title: str
    description: str
    updated: str
    body: str

    @property
    def url(self) -> str:
        """Where the page lives online."""
        return f"{_SITE}/docs" if self.slug == "index" else f"{_SITE}/docs/{self.slug}"


def _parse(slug: str, text: str) -> Doc:
    text = text.replace("\r\n", "\n")
    match = _FRONT_MATTER.match(text)
    if match is None:
        raise ValueError(f"The {slug} doc has no front matter")
    fields = {
        key.strip(): value.strip()
        for key, _, value in (line.partition(":") for line in match["fields"].splitlines())
    }
    return Doc(
        slug=slug,
        title=fields["title"],
        description=fields["description"],
        updated=fields["updated"],
        body=text[match.end() :].strip() + "\n",
    )


def all_docs() -> list[Doc]:
    """Every page, the quickstart first, then by name."""
    pages = [
        _parse(item.name.removesuffix(".md"), item.read_text(encoding="utf-8"))
        for item in resources.files(__package__).iterdir()
        if item.name.endswith(".md")
    ]
    return sorted(pages, key=lambda doc: (doc.slug != "index", doc.slug))


def get(slug: str) -> Doc:
    """One page by its slug.

    Raises:
        KeyError: when there is no such page.
    """
    for doc in all_docs():
        if doc.slug == slug:
            return doc
    raise KeyError(slug)
