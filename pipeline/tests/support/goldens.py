"""Golden fixtures: real snapshots saved by ``settlecal-pipeline save-fixture``.

Each ``tests/fixtures/<source id>/<name>.gz`` holds the exact bytes a source served,
``<name>.meta.json`` says where and when they came from, and ``<name>.expected.json``
holds the observations the source's parser must produce from them.
"""

import gzip
import json
from dataclasses import asdict, dataclass
from datetime import date, datetime
from pathlib import Path

from settlecal_pipeline.models import Observation, SnapshotMeta

FIXTURES = Path(__file__).parents[1] / "fixtures"


@dataclass(frozen=True)
class Golden:
    source_id: str
    name: str
    folder: Path

    @property
    def content(self) -> bytes:
        return gzip.decompress((self.folder / f"{self.name}.gz").read_bytes())

    @property
    def meta(self) -> SnapshotMeta:
        data = json.loads((self.folder / f"{self.name}.meta.json").read_text(encoding="utf-8"))
        return SnapshotMeta(
            source_id=self.source_id,
            url=data["url"],
            fetched_at=datetime.fromisoformat(data["fetched_at"]),
            content_type=data["content_type"],
            sha256=data["sha256"],
        )

    @property
    def expected_path(self) -> Path:
        return self.folder / f"{self.name}.expected.json"

    def expected(self) -> list[Observation]:
        rows = json.loads(self.expected_path.read_text(encoding="utf-8"))
        return [
            Observation(
                row["calendar_id"],
                date.fromisoformat(row["entry_date"]),
                row["is_open"],
                row["reason"],
            )
            for row in rows
        ]


def goldens(source_ids: set[str]) -> list[Golden]:
    return [
        Golden(folder.name, snapshot.name.removesuffix(".gz"), folder)
        for folder in sorted(FIXTURES.iterdir())
        if folder.is_dir() and folder.name in source_ids
        for snapshot in sorted(folder.glob("*.gz"))
    ]


def to_json(observations: list[Observation]) -> str:
    rows = [{**asdict(o), "entry_date": o.entry_date.isoformat()} for o in observations]
    return json.dumps(rows, indent=1, ensure_ascii=False) + "\n"
