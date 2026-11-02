"""Raw snapshot storage, keyed by SHA-256."""

from typing import Protocol

from google.api_core.exceptions import PreconditionFailed
from google.auth.credentials import AnonymousCredentials
from google.cloud import storage


def snapshot_key(sha256_hex: str) -> str:
    return f"sha256/{sha256_hex}"


def gcs_client(endpoint: str | None = None) -> storage.Client:
    """Uses Application Default Credentials, or no credentials for a local ``endpoint``."""
    if endpoint is None:
        return storage.Client()
    return storage.Client(
        project="local",
        credentials=AnonymousCredentials(),  # type: ignore[no-untyped-call]
        client_options={"api_endpoint": endpoint},
    )


class SnapshotStore(Protocol):
    def put(self, key: str, content: bytes, content_type: str) -> None:
        """Stores ``content`` under ``key`` once. Storing the same key again is a no-op."""
        ...


class GcsSnapshotStore:
    """Cloud Storage, or fake-gcs-server locally. Objects are create-only."""

    def __init__(self, client: storage.Client, bucket: str) -> None:
        self._bucket = client.bucket(bucket)

    @classmethod
    def connect(cls, bucket: str, endpoint: str | None = None) -> "GcsSnapshotStore":
        return cls(gcs_client(endpoint), bucket)

    def put(self, key: str, content: bytes, content_type: str) -> None:
        blob = self._bucket.blob(key)
        try:
            # Generation 0 means "only if absent": the pipeline may create objects but
            # never overwrite evidence. The same key always holds the same bytes.
            blob.upload_from_string(content, content_type=content_type, if_generation_match=0)
        except PreconditionFailed:
            return
