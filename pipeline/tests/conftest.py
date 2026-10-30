"""Containers shared by the database tests: Postgres 18 (as on Aiven) and fake-gcs-server.

Each test gets a fresh database migrated with api/'s Flyway migrations, in order, and
the least-privilege ``zeitwide_pipeline`` login the jobs use on Cloud Run.
"""

import re
import time
from collections.abc import Iterator
from pathlib import Path

import httpx
import psycopg
import pytest
from google.cloud import storage
from testcontainers.community.postgres import PostgresContainer
from testcontainers.core.container import DockerContainer

from support.database import BUCKET, PIPELINE_LOGIN, PIPELINE_PASSWORD, Database
from zeitwide_pipeline.storage import GcsSnapshotStore

MIGRATIONS = Path(__file__).parents[2] / "api" / "src" / "main" / "resources" / "db" / "migration"
FAKE_GCS_IMAGE = "fsouza/fake-gcs-server:1.55.1"
FAKE_GCS_PORT = 4443
STARTUP_TIMEOUT_S = 30


def _version(path: Path) -> int:
    match = re.match(r"V(\d+)__", path.name)
    if match is None:
        raise ValueError(f"Not a versioned migration: {path.name}")
    return int(match.group(1))


@pytest.fixture(scope="session")
def postgres() -> Iterator[PostgresContainer]:
    with PostgresContainer("postgres:18.6-alpine", driver=None) as container:
        yield container


@pytest.fixture(scope="session")
def gcs_endpoint() -> Iterator[str]:
    container = (
        DockerContainer(FAKE_GCS_IMAGE)
        .with_command(f"-scheme http -port {FAKE_GCS_PORT} -backend memory")
        .with_exposed_ports(FAKE_GCS_PORT)
    )
    with container:
        endpoint = f"http://{container.get_container_host_ip()}:{container.get_exposed_port(FAKE_GCS_PORT)}"
        _wait_for_http(f"{endpoint}/storage/v1/b")
        yield endpoint


def _wait_for_http(url: str) -> None:
    deadline = time.monotonic() + STARTUP_TIMEOUT_S
    while True:
        try:
            if httpx.get(url, timeout=1).status_code < httpx.codes.INTERNAL_SERVER_ERROR:
                return
        except httpx.TransportError:
            if time.monotonic() > deadline:
                raise
        time.sleep(0.2)


@pytest.fixture
def migrated(postgres: PostgresContainer) -> Iterator[Database]:
    """A freshly migrated database per test, plus the pipeline's own login."""
    admin = postgres.get_connection_url()
    name = f"test_{time.monotonic_ns()}"
    with psycopg.connect(admin, autocommit=True) as connection:
        connection.execute(f'CREATE DATABASE "{name}"')
        connection.execute(
            f"""
            DO $$ BEGIN
                CREATE ROLE {PIPELINE_LOGIN} LOGIN PASSWORD '{PIPELINE_PASSWORD}';
            EXCEPTION WHEN duplicate_object THEN NULL; END $$
            """
        )
    database_admin = admin.rsplit("/", 1)[0] + f"/{name}"
    with psycopg.connect(database_admin, autocommit=True) as connection:
        for migration in sorted(MIGRATIONS.glob("V*__*.sql"), key=_version):
            connection.execute(migration.read_text(encoding="utf-8"))
        connection.execute((MIGRATIONS / "afterMigrate.sql").read_text(encoding="utf-8"))
    host = postgres.get_container_host_ip()
    port = postgres.get_exposed_port(5432)
    yield Database(
        admin=database_admin,
        pipeline=f"postgresql://{PIPELINE_LOGIN}:{PIPELINE_PASSWORD}@{host}:{port}/{name}",
    )
    with psycopg.connect(admin, autocommit=True) as connection:
        connection.execute(f'DROP DATABASE "{name}" WITH (FORCE)')


@pytest.fixture
def bucket(gcs_endpoint: str) -> storage.Bucket:
    store = GcsSnapshotStore.connect(BUCKET, gcs_endpoint)
    client = store._bucket.client
    found = client.lookup_bucket(BUCKET)
    return found if found is not None else client.create_bucket(BUCKET)
