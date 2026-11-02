"""Configuration from environment variables. Read in the CLI, never at import time."""

from pydantic import Field, HttpUrl, SecretStr, field_validator
from pydantic_settings import BaseSettings, SettingsConfigDict

# One connection for Procrastinate, one for the running check (concurrency is 1).
MIN_DB_CONNECTIONS = 2
# Aiven's free plan allows 20 connections in total; see deploy/gcp/README.md.
MAX_DB_CONNECTIONS = 5


class FetchSettings(BaseSettings):
    """What fetching alone needs, for dry runs and saving fixtures."""

    model_config = SettingsConfigDict(env_prefix="ZEITWIDE_", frozen=True)

    # Sent in the User-Agent so source operators can see who is fetching and why.
    bot_contact_url: HttpUrl

    @field_validator("bot_contact_url")
    @classmethod
    def _https_only(cls, value: HttpUrl) -> HttpUrl:
        if value.scheme != "https":
            raise ValueError("bot_contact_url must be an https URL")
        return value


class Settings(FetchSettings):
    # Empty means libpq's PG* variables (PGHOST, PGUSER, PGSSLMODE...), as on Cloud Run.
    database_conninfo: str = ""
    db_pool_max_size: int = Field(
        default=MIN_DB_CONNECTIONS, ge=MIN_DB_CONNECTIONS, le=MAX_DB_CONNECTIONS
    )
    snapshot_bucket: str = Field(min_length=3, max_length=63)
    # fake-gcs-server locally, such as http://localhost:4443; unset on GCP.
    gcs_endpoint: str | None = None
    otlp_endpoint: str | None = Field(default=None, validation_alias="OTEL_EXPORTER_OTLP_ENDPOINT")

    # The daily job. Unset parts are skipped with a warning, for local runs.
    backup_bucket: str | None = None
    backup_db_user: str | None = None
    backup_db_password: SecretStr | None = None
    # Comma-separated, such as "zeitwide".
    backup_databases: str = ""
    dataset_repository: str | None = None
    github_token: SecretStr | None = Field(default=None, validation_alias="GITHUB_TOKEN")
    # Site files (llms.txt, sitemaps, robots.txt) for each release; skipped when unset.
    site_files_bucket: str | None = None
    site_url: HttpUrl | None = None
    api_public_url: HttpUrl | None = None

    def databases_to_back_up(self) -> list[str]:
        return [name.strip() for name in self.backup_databases.split(",") if name.strip()]
