"""Daily ``pg_dump`` of each database to the backups bucket (its lifecycle keeps 30 days).

Aiven's free plan has no point-in-time recovery, so these dumps are the only backup.
"""

import os
import subprocess
import tempfile
from collections.abc import Callable, Mapping, Sequence
from pathlib import Path

from settlecal_pipeline.clock import Clock
from settlecal_pipeline.storage import SnapshotStore

DUMP_TIMEOUT_S = 15 * 60
DUMP_CONTENT_TYPE = "application/octet-stream"

type DumpRunner = Callable[[Sequence[str], Mapping[str, str], Path], None]


def run_pg_dump(args: Sequence[str], env: Mapping[str, str], output: Path) -> None:
    """Runs pg_dump without a shell; the password reaches it only through the environment."""
    with output.open("wb") as target:
        subprocess.run(  # noqa: S603 - fixed program and arguments, no shell
            list(args), env=dict(env), stdout=target, check=True, timeout=DUMP_TIMEOUT_S
        )


class DatabaseBackup:
    def __init__(  # noqa: PLR0913 - every collaborator is injected for tests
        self,
        store: SnapshotStore,
        clock: Clock,
        *,
        user: str,
        password: str,
        databases: Sequence[str],
        runner: DumpRunner = run_pg_dump,
    ) -> None:
        self._store = store
        self._clock = clock
        self._user = user
        self._password = password
        self._databases = list(databases)
        self._runner = runner

    def run(self) -> list[str]:
        """Dumps every database and returns the object keys written."""
        stamp = self._clock().strftime("%Y%m%dT%H%M%SZ")
        # Host, port and TLS settings come from the job's PG* variables.
        env = {**os.environ, "PGUSER": self._user, "PGPASSWORD": self._password}
        keys = []
        with tempfile.TemporaryDirectory() as folder:
            for database in self._databases:
                output = Path(folder) / f"{database}.dump"
                self._runner(
                    [
                        "pg_dump",
                        "--format=custom",
                        "--no-owner",
                        "--no-privileges",
                        f"--dbname={database}",
                    ],
                    env,
                    output,
                )
                key = f"pg_dump/{database}/{stamp}.dump"
                self._store.put(key, output.read_bytes(), DUMP_CONTENT_TYPE)
                keys.append(key)
        return keys
