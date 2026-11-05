"""Builds the open-data bundles for a release and publishes them, or writes them to a folder.

``dry_run`` is the local mode: it builds every artefact, writes them under ``out/<target>/``,
reads them back and checks them against their checksums and the release's, and validates the
dataset card. ``OpenData.publish`` is what the daily job calls with the release it just cut.
"""

import json
from collections.abc import Mapping
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

import structlog

from zeitwide_pipeline.distribution.bundle import (
    HUGGING_FACE,
    SUMS,
    Bundle,
    Destinations,
    Outcome,
    build_bundles,
    sha256_hex,
    verify,
)
from zeitwide_pipeline.distribution.hub import CARD, validate_card
from zeitwide_pipeline.site.links import SiteLinks

log = structlog.get_logger(__name__)


class OpenDataError(Exception):
    """A bundle failed its checks, or a target refused it."""


class Target(Protocol):
    def publish(self, bundle: Bundle) -> Outcome: ...


@dataclass(frozen=True)
class OpenData:
    links: SiteLinks
    destinations: Destinations
    # Bundle target name (``github``, ``huggingface``) to where it goes.
    targets: Mapping[str, Target]

    def publish(self, content: bytes) -> dict[str, Outcome]:
        """Builds from the release export's exact bytes and pushes each bundle.

        A failing target doesn't stop the others; the error is raised once all were tried.
        """
        bundles = build_for(content, self.links, self.destinations)
        results: dict[str, Outcome] = {}
        failed: list[str] = []
        for bundle in bundles:
            target = self.targets.get(bundle.target)
            if target is None:
                log.warning("open_data_skipped", target=bundle.target, reason="not configured")
                continue
            try:
                results[bundle.target] = target.publish(bundle)
            except Exception as error:
                failed.append(bundle.target)
                log.exception("open_data_failed", target=bundle.target, error=str(error))
            else:
                log.info(
                    "open_data_done",
                    target=bundle.target,
                    release=bundle.release,
                    outcome=results[bundle.target],
                )
        if failed:
            raise OpenDataError(f"Publishing failed for {', '.join(failed)}")
        return results


def build_for(content: bytes, links: SiteLinks, destinations: Destinations) -> list[Bundle]:
    """Bundles for the release export ``content``, checked against its own checksum."""
    checksum = sha256_hex(content)
    bundles = build_bundles(json.loads(content), links, destinations, release_sha256=checksum)
    problems = [
        problem for bundle in bundles for problem in verify(bundle, release_sha256=checksum)
    ]
    if problems:
        raise OpenDataError("; ".join(problems))
    return bundles


def dry_run(  # noqa: PLR0913
    content: bytes,
    out: Path,
    links: SiteLinks,
    destinations: Destinations,
    *,
    expect_sha256: str | None = None,
    validate_online: bool = False,
) -> list[Bundle]:
    """Writes every artefact under ``out`` and proves what was written.

    ``expect_sha256`` is the checksum the database recorded for the release; a file that
    hashes differently isn't that release.
    """
    checksum = sha256_hex(content)
    if expect_sha256 is not None and checksum != expect_sha256:
        raise OpenDataError("The release file does not match the checksum recorded for it")
    bundles = build_for(content, links, destinations)
    problems: list[str] = []
    for bundle in bundles:
        folder = out / bundle.target
        for file in bundle.files:
            target = folder / file.path
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(file.content)
        problems += check_folder(folder, bundle)
        if bundle.target == HUGGING_FACE:
            validate_card((folder / CARD).read_text(encoding="utf-8"), online=validate_online)
    if problems:
        raise OpenDataError("; ".join(problems))
    return bundles


def check_folder(folder: Path, bundle: Bundle) -> list[str]:
    """Re-reads the written files and compares them with the SHA256SUMS written beside them."""
    problems: list[str] = []
    sums = (folder / SUMS).read_text(encoding="utf-8").splitlines()
    listed = {path: digest for digest, _, path in (line.partition("  ") for line in sums)}
    for file in bundle.files:
        if file.path == SUMS:
            continue
        written = folder / file.path
        if not written.is_file():
            problems.append(f"{bundle.target}: {file.path} was not written")
        elif listed.get(file.path) != sha256_hex(written.read_bytes()):
            problems.append(f"{bundle.target}: {file.path} on disk does not match {SUMS}")
    return problems
