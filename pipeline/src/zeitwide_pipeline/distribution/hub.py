"""Pushes a bundle to the Hugging Face dataset repository, and checks its dataset card.

``HubStore`` is the small surface this needs from the Hub, so tests and dry runs never
touch the network; ``HfHubStore`` is the real one, using ``huggingface_hub``.
"""

import json
import tempfile
from collections.abc import Mapping, Sequence
from pathlib import Path
from typing import Protocol

import yaml
from huggingface_hub import CommitOperationAdd, CommitOperationDelete, DatasetCard, HfApi
from huggingface_hub.errors import EntryNotFoundError, RepositoryNotFoundError

from zeitwide_pipeline.distribution.bundle import MANIFEST, Bundle, Outcome

CARD = "README.md"
_REQUIRED_CARD_FIELDS = ("license", "task_categories", "size_categories", "pretty_name", "configs")


class CardError(Exception):
    """The dataset card would not pass the Hub's metadata validation."""


def validate_card(text: str, *, online: bool = False) -> None:
    """Parses the card with huggingface_hub and checks the fields the Hub needs.

    ``online`` also asks the Hub's own validator (no token needed), which is what the Hub
    runs on a push.
    """
    try:
        card = DatasetCard(text)
    except (ValueError, OSError, yaml.YAMLError) as error:
        raise CardError(f"The dataset card does not parse: {error}") from error
    data = card.data.to_dict()  # type: ignore[no-untyped-call]
    missing = [field for field in _REQUIRED_CARD_FIELDS if not data.get(field)]
    if missing:
        raise CardError(f"The dataset card metadata lacks {', '.join(missing)}")
    if not card.text.strip().startswith("# "):
        raise CardError("The dataset card has no body")
    if online:
        try:
            card.validate("dataset")
        except ValueError as error:
            raise CardError(str(error)) from error


class HubStore(Protocol):
    def read_text(self, path: str) -> str | None:
        """A file's text on the default branch, or None when the repo or file is missing."""
        ...

    def files(self) -> list[str]: ...

    def has_tag(self, name: str) -> bool: ...

    def head(self) -> str:
        """The commit the default branch points at."""
        ...

    def commit(self, files: Mapping[str, bytes], delete: Sequence[str], message: str) -> str:
        """One commit adding ``files`` and removing ``delete``; returns its ID."""
        ...

    def tag(self, name: str, revision: str) -> None: ...


class HubDataset:
    def __init__(self, store: HubStore, *, validate_online: bool = True) -> None:
        self._store = store
        self._validate_online = validate_online

    def publish(self, bundle: Bundle) -> Outcome:
        if self._store.has_tag(bundle.release):
            return Outcome.ALREADY_PUBLISHED
        validate_card(bundle.file(CARD).content.decode(), online=self._validate_online)
        remote = self._remote_manifest()
        if remote.get("release") == bundle.release:
            # The commit landed in an earlier run, which stopped before tagging it.
            self._store.tag(bundle.release, self._store.head())
            return Outcome.PUBLISHED
        if remote.get("data_digest") == bundle.data_digest:
            return Outcome.UNCHANGED
        stale = sorted(set(self._store.files()) - set(bundle.paths) - {".gitattributes"})
        commit = self._store.commit(
            {file.path: file.content for file in bundle.files},
            stale,
            f"Dataset release {bundle.release}",
        )
        self._store.tag(bundle.release, commit)
        return Outcome.PUBLISHED

    def _remote_manifest(self) -> dict[str, object]:
        text = self._store.read_text(MANIFEST)
        if text is None:
            return {}
        try:
            manifest = json.loads(text)
        except ValueError:
            return {}
        return manifest if isinstance(manifest, dict) else {}


class HfHubStore:
    """The dataset ``repo_id`` on the Hub, created on first use."""

    def __init__(self, api: HfApi, repo_id: str) -> None:
        self._api = api
        self._repo = repo_id

    def read_text(self, path: str) -> str | None:
        with tempfile.TemporaryDirectory() as folder:
            try:
                local = self._api.hf_hub_download(
                    self._repo, path, repo_type="dataset", local_dir=folder
                )
            except (EntryNotFoundError, RepositoryNotFoundError):
                return None
            return Path(local).read_text(encoding="utf-8")

    def files(self) -> list[str]:
        try:
            return list(self._api.list_repo_files(self._repo, repo_type="dataset"))
        except RepositoryNotFoundError:
            return []

    def has_tag(self, name: str) -> bool:
        try:
            refs = self._api.list_repo_refs(self._repo, repo_type="dataset")
        except RepositoryNotFoundError:
            return False
        return any(tag.name == name for tag in refs.tags)

    def head(self) -> str:
        info = self._api.dataset_info(self._repo)
        if info.sha is None:
            raise RuntimeError("The Hub reports no commit for the dataset")
        return info.sha

    def commit(self, files: Mapping[str, bytes], delete: Sequence[str], message: str) -> str:
        # The first release creates the dataset.
        self._api.create_repo(self._repo, repo_type="dataset", exist_ok=True)
        operations: list[CommitOperationAdd | CommitOperationDelete] = [
            CommitOperationAdd(path_in_repo=path, path_or_fileobj=content)
            for path, content in files.items()
        ]
        operations += [CommitOperationDelete(path_in_repo=path) for path in delete]
        info = self._api.create_commit(
            self._repo, operations, commit_message=message, repo_type="dataset"
        )
        return str(info.oid)

    def tag(self, name: str, revision: str) -> None:
        self._api.create_tag(
            self._repo, tag=name, revision=revision, repo_type="dataset", exist_ok=True
        )
