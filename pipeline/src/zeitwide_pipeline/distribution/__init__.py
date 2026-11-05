"""The open dataset, published where models, agents and developers already look.

From one dataset release this builds the files for the GitHub data repository (CSV, JSON,
README, CITATION.cff, LICENSE, CHANGELOG.md) and the Hugging Face dataset (Parquet and a
dataset card), each with a manifest and checksums, and pushes them. Nothing is written by
hand: a release in, artefacts out.
"""

from zeitwide_pipeline.distribution.bundle import (
    GITHUB,
    HUGGING_FACE,
    Bundle,
    Destinations,
    File,
    Outcome,
)
from zeitwide_pipeline.distribution.github_data import DataRepository
from zeitwide_pipeline.distribution.hub import CardError, HfHubStore, HubDataset, validate_card
from zeitwide_pipeline.distribution.publisher import OpenData, OpenDataError, dry_run

__all__ = [
    "GITHUB",
    "HUGGING_FACE",
    "Bundle",
    "CardError",
    "DataRepository",
    "Destinations",
    "File",
    "HfHubStore",
    "HubDataset",
    "OpenData",
    "OpenDataError",
    "Outcome",
    "dry_run",
    "validate_card",
]
