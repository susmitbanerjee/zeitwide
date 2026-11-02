"""The public docs, synced from docs/site/*.md by ``make sync-docs`` into this package."""

import json
import re
from dataclasses import dataclass
from importlib import resources
from typing import cast

_FRONT_MATTER = re.compile(r"\A---\n(?P<fields>.*?)\n---\n", re.DOTALL)


@dataclass(frozen=True, slots=True)
class Doc:
    slug: str
    title: str
    description: str
    updated: str
    body: str

    @property
    def path(self) -> str:
        return "/docs" if self.slug == "index" else f"/docs/{self.slug}"


def parse_doc(slug: str, text: str) -> Doc:
    match = _FRONT_MATTER.match(text.replace("\r\n", "\n"))
    if match is None:
        raise ValueError(f"docs/site/{slug}.md has no front matter")
    fields = {
        key.strip(): value.strip()
        for key, _, value in (line.partition(":") for line in match["fields"].splitlines())
    }
    missing = [name for name in ("title", "description", "updated") if not fields.get(name)]
    if missing:
        raise ValueError(f"docs/site/{slug}.md has no {', '.join(missing)}")
    return Doc(
        slug=slug,
        title=fields["title"],
        description=fields["description"],
        updated=fields["updated"],
        body=text.replace("\r\n", "\n")[match.end() :].strip() + "\n",
    )


def load_docs() -> list[Doc]:
    """Every doc, the index first, then by slug."""
    folder = resources.files(__package__).joinpath("docs")
    docs = [
        parse_doc(item.name.removesuffix(".md"), item.read_text(encoding="utf-8"))
        for item in folder.iterdir()
        if item.name.endswith(".md")
    ]
    return sorted(docs, key=lambda doc: (doc.slug != "index", doc.slug))


def load_openapi() -> dict[str, object]:
    text = resources.files(__package__).joinpath("openapi.json").read_text(encoding="utf-8")
    return cast("dict[str, object]", json.loads(text))
