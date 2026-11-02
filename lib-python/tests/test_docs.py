"""zeitwide.docs: the docs/site pages, packaged for offline use."""

from pathlib import Path

import pytest

from zeitwide import docs

SOURCE = Path(__file__).resolve().parents[2] / "docs" / "site"


def test_packaged_docs_match_docs_site() -> None:
    """`make sync-docs` copies docs/site/*.md into the package."""
    packaged = {doc.slug: doc for doc in docs.all_docs()}
    assert set(packaged) == {path.stem for path in SOURCE.glob("*.md")}, "run make sync-docs"
    for path in SOURCE.glob("*.md"):
        text = path.read_text(encoding="utf-8")
        assert packaged[path.stem].body.strip() in text, "run make sync-docs"


def test_docs_have_titles_descriptions_and_urls() -> None:
    quickstart = docs.get("index")
    assert quickstart.title == "Developer quickstart"
    assert quickstart.url == "https://zeitwide.com/docs"
    assert docs.get("rules").url == "https://zeitwide.com/docs/rules"
    assert all(doc.description and doc.updated for doc in docs.all_docs())
    assert docs.all_docs()[0].slug == "index"
    with pytest.raises(KeyError):
        docs.get("nope")
