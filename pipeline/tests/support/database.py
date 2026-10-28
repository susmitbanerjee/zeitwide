"""The test database's names and logins, shared by fixtures and tests."""

from dataclasses import dataclass

PIPELINE_LOGIN = "settlecal_pipeline"
PIPELINE_PASSWORD = "pipeline-test-only"  # noqa: S105 - throwaway container login
BUCKET = "settlecal-snapshots"


@dataclass(frozen=True)
class Database:
    admin: str
    pipeline: str
