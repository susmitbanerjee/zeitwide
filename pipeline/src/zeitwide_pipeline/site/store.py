"""Where the site files go: the public site-files bucket, or a folder for local runs."""

from collections.abc import Sequence
from contextlib import suppress
from pathlib import Path

from google.api_core.exceptions import PreconditionFailed
from google.cloud import storage

from zeitwide_pipeline.site import CACHE_CONTROL, SiteFile

CURRENT = "current"


class GcsSiteFileStore:
    """Each release's files under ``{release}/`` (create-only), and the served copy under
    ``current/``, which the web app reads through the bucket's public URL."""

    def __init__(self, client: storage.Client, bucket: str) -> None:
        self._bucket = client.bucket(bucket)

    def publish(self, release_id: str, files: Sequence[SiteFile]) -> None:
        for file in files:
            kept = self._bucket.blob(f"{release_id}/{file.name}")
            kept.cache_control = CACHE_CONTROL
            # A retried run finds the release's copy already there.
            with suppress(PreconditionFailed):
                kept.upload_from_string(
                    file.content, content_type=file.content_type, if_generation_match=0
                )
        # The release's own copies first and the served ones last, so a run that fails part-way
        # leaves a complete copy to retry from.
        for file in files:
            served = self._bucket.blob(f"{CURRENT}/{file.name}")
            served.cache_control = CACHE_CONTROL
            served.upload_from_string(file.content, content_type=file.content_type)


class DirectorySiteFileStore:
    """Writes the served copy into a folder, for local runs and the end-to-end tests."""

    def __init__(self, root: Path) -> None:
        self._root = root

    def publish(self, release_id: str, files: Sequence[SiteFile]) -> None:  # noqa: ARG002
        for file in files:
            target = self._root / file.name
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(file.content)
