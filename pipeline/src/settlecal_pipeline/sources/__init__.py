"""Source and parser protocols, and the registry that collects one module per source."""

from settlecal_pipeline.sources.base import (
    ParseError,
    Parser,
    Source,
    SourceDefinition,
    parse_snapshot,
)
from settlecal_pipeline.sources.registry import (
    SourceRegistry,
    UnknownSourceError,
    builtin_registry,
)

__all__ = [
    "ParseError",
    "Parser",
    "Source",
    "SourceDefinition",
    "SourceRegistry",
    "UnknownSourceError",
    "builtin_registry",
    "parse_snapshot",
]
