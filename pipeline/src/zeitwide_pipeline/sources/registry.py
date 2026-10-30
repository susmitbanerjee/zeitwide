"""Collects sources. Adding a source means adding a module, never editing this file."""

import importlib
import pkgutil
from collections.abc import Iterable, Iterator

from zeitwide_pipeline.sources import catalog
from zeitwide_pipeline.sources.base import Source


class UnknownSourceError(KeyError):
    """No registered source has the requested ID."""


class SourceRegistry:
    """Sources by ID."""

    def __init__(self, sources: Iterable[Source] = ()) -> None:
        self._sources: dict[str, Source] = {}
        for source in sources:
            self.register(source)

    def register(self, source: Source) -> None:
        if source.id in self._sources:
            raise ValueError(f"Source {source.id!r} is registered twice")
        self._sources[source.id] = source

    def get(self, source_id: str) -> Source:
        try:
            return self._sources[source_id]
        except KeyError:
            raise UnknownSourceError(source_id) from None

    def __iter__(self) -> Iterator[Source]:
        return iter(self._sources.values())

    def __len__(self) -> int:
        return len(self._sources)


def builtin_registry() -> SourceRegistry:
    """Every module in ``sources/catalog/`` that defines a ``SOURCE``."""
    registry = SourceRegistry()
    for module_info in pkgutil.iter_modules(catalog.__path__):
        module = importlib.import_module(f"{catalog.__name__}.{module_info.name}")
        source: Source | None = getattr(module, "SOURCE", None)
        if source is None:
            raise ValueError(f"{module.__name__} defines no SOURCE")
        registry.register(source)
    return registry
