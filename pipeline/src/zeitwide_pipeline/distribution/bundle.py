"""The files published to each place, sealed with a manifest and checksums.

Two bundles are built from one release: the GitHub data repository (CSV and JSON) and the
Hugging Face dataset (Parquet and the dataset card). Each carries ``MANIFEST.json`` and
``SHA256SUMS``. The manifest names the release export's own SHA-256, so every file can be
traced to the release the database recorded, and a ``data_digest`` over the CSV tables, which
hold nothing about the release itself. A target skips publishing when the digest it already
holds equals the new one.
"""

import hashlib
import json
from collections.abc import Mapping
from dataclasses import dataclass
from enum import StrEnum

from zeitwide_pipeline.distribution import texts
from zeitwide_pipeline.distribution.changelog import changelog_md
from zeitwide_pipeline.distribution.tables import (
    INDEX_NAME,
    calendar_csv,
    calendar_json,
    calendar_parquet,
    calendars_csv,
    csv_name,
    json_name,
    parquet_name,
)
from zeitwide_pipeline.site.links import SiteLinks
from zeitwide_pipeline.site.release_view import ReleaseView, read_release

MANIFEST = "MANIFEST.json"
SUMS = "SHA256SUMS"
GITHUB = "github"
HUGGING_FACE = "huggingface"
MANIFEST_VERSION = 1


class Outcome(StrEnum):
    PUBLISHED = "published"
    # The release's data is what the target already holds, so nothing was pushed.
    UNCHANGED = "unchanged"
    # An earlier run already published this release.
    ALREADY_PUBLISHED = "already_published"


@dataclass(frozen=True, slots=True)
class File:
    path: str
    content: bytes

    @property
    def sha256(self) -> str:
        return sha256_hex(self.content)


@dataclass(frozen=True, slots=True)
class Bundle:
    target: str
    release: str
    data_digest: str
    files: tuple[File, ...]

    def file(self, path: str) -> File:
        for file in self.files:
            if file.path == path:
                return file
        raise KeyError(path)

    @property
    def paths(self) -> list[str]:
        return [file.path for file in self.files]


@dataclass(frozen=True, slots=True)
class Destinations:
    """Where the bundles go: only for the links each file carries back."""

    github_repository: str
    hub_repository: str


def sha256_hex(content: bytes) -> str:
    return hashlib.sha256(content).hexdigest()


def build_bundles(
    document: Mapping[str, object],
    links: SiteLinks,
    destinations: Destinations,
    *,
    release_sha256: str,
) -> list[Bundle]:
    """Both bundles. ``release_sha256`` is the release export's checksum, as recorded."""
    release = read_release(document)
    csvs = [File(INDEX_NAME, calendars_csv(release, links))] + [
        File(csv_name(calendar.id), calendar_csv(calendar)) for calendar in release.calendars
    ]
    digest = data_digest(csvs)
    inputs = _Inputs(release, links, destinations, csvs, digest, release_sha256)
    return [_github(document, inputs), _hub(inputs)]


def data_digest(tables: list[File]) -> str:
    """Over the CSV tables, which are a function of the data alone."""
    lines = sorted(f"{file.sha256}  {file.path}" for file in tables)
    return sha256_hex("\n".join(lines).encode())


def verify(bundle: Bundle, *, release_sha256: str | None = None) -> list[str]:
    """Problems found re-reading the bundle against its own manifest; empty when sound."""
    problems: list[str] = []
    manifest = json.loads(bundle.file(MANIFEST).content)
    for path, expected in manifest["files"].items():
        try:
            actual = bundle.file(path).sha256
        except KeyError:
            problems.append(f"{bundle.target}: {path} is in the manifest but not the bundle")
            continue
        if actual != expected:
            problems.append(f"{bundle.target}: {path} does not match its manifest checksum")
    sums = bundle.file(SUMS).content.decode().splitlines()
    problems += [
        f"{bundle.target}: {file.path} is not in {SUMS} with this checksum"
        for file in bundle.files
        if file.path != SUMS and f"{file.sha256}  {file.path}" not in sums
    ]
    if release_sha256 is not None and manifest["release_sha256"] != release_sha256:
        problems.append(f"{bundle.target}: built from a release with a different checksum")
    return problems


@dataclass(frozen=True, slots=True)
class _Inputs:
    release: ReleaseView
    links: SiteLinks
    destinations: Destinations
    csvs: list[File]
    digest: str
    release_sha256: str


def _github(document: Mapping[str, object], inputs: _Inputs) -> Bundle:
    release, links, destinations = inputs.release, inputs.links, inputs.destinations
    files = inputs.csvs + [
        File(json_name(calendar.id), calendar_json(release, calendar, links))
        for calendar in release.calendars
    ]
    files += [
        File("README.md", texts.github_readme(release, links, destinations).encode()),
        File("CITATION.cff", texts.citation_cff(release, links, destinations).encode()),
        File("LICENSE", texts.LICENSE_TEXT.encode()),
        File("CHANGELOG.md", changelog_md(document, release, links).encode()),
    ]
    return _seal(GITHUB, inputs, files)


def _hub(inputs: _Inputs) -> Bundle:
    release, links, destinations = inputs.release, inputs.links, inputs.destinations
    files = [inputs.csvs[0]] + [
        File(parquet_name(calendar.id), calendar_parquet(release, calendar, links))
        for calendar in release.calendars
    ]
    files += [
        File("README.md", texts.dataset_card(release, links, destinations).encode()),
        File("LICENSE", texts.LICENSE_TEXT.encode()),
    ]
    return _seal(HUGGING_FACE, inputs, files)


def _seal(target: str, inputs: _Inputs, files: list[File]) -> Bundle:
    release = inputs.release
    manifest = {
        "manifest_version": MANIFEST_VERSION,
        "target": target,
        "release": release.id,
        "as_of": release.as_of.isoformat().replace("+00:00", "Z"),
        "release_sha256": inputs.release_sha256,
        "data_digest": inputs.digest,
        "files": {file.path: file.sha256 for file in sorted(files, key=lambda f: f.path)},
    }
    manifest_file = File(MANIFEST, (json.dumps(manifest, indent=2) + "\n").encode())
    listed = sorted([*files, manifest_file], key=lambda f: f.path)
    sums = File(SUMS, "".join(f"{file.sha256}  {file.path}\n" for file in listed).encode())
    return Bundle(target, release.id, inputs.digest, (*listed, sums))
