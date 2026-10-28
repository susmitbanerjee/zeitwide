"""Loads every calendar definition shipped in ``catalog/``."""

import json
from functools import cache
from importlib import resources

from settlecal_pipeline.calendars.model import CalendarDefinition


@cache
def load_catalog() -> dict[str, CalendarDefinition]:
    """Calendar definitions by ID. Raises if a file is invalid or named after another ID."""
    definitions: dict[str, CalendarDefinition] = {}
    folder = resources.files("settlecal_pipeline.calendars").joinpath("catalog")
    for entry in sorted(folder.iterdir(), key=lambda item: item.name):
        if not entry.name.endswith(".json"):
            continue
        definition = CalendarDefinition.model_validate(json.loads(entry.read_text("utf-8")))
        if entry.name != f"{definition.id}.json":
            raise ValueError(f"{entry.name} defines {definition.id}; name it {definition.id}.json")
        definitions[definition.id] = definition
    return definitions
