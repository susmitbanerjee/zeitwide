"""A release keeps every entry version recorded by its as_of, on real Postgres."""

import json
from datetime import UTC, date, datetime
from pathlib import Path

import jsonschema
import psycopg
from settlecal.engine import is_business_day
from settlecal.release import parse_release

from settlecal_pipeline.release import PostgresReleaseStore
from support.database import Database

RELEASE_SCHEMA = Path(__file__).parents[2] / "dataset" / "schema" / "release.schema.json"
AS_OF = datetime(2026, 8, 1, tzinfo=UTC)
CHRISTMAS = date(2026, 12, 25)
THANKSGIVING = date(2026, 11, 26)


def test_export_keeps_replaced_versions_and_leaves_out_later_ones(migrated: Database) -> None:
    with psycopg.connect(migrated.admin, autocommit=True) as connection:
        connection.execute(
            """
            INSERT INTO calendar (id, name, kind, timezone, weekend_rule)
            VALUES ('US-FEDWIRE', 'Fedwire Funds Service', 'rail', 'America/New_York',
                    '{"days": ["saturday", "sunday"]}');
            SELECT publish_entry_version('US-FEDWIRE', DATE '2026-12-25', false, 'Christmas',
                                         'projected', 1, TIMESTAMPTZ '2026-01-01Z');
            SELECT publish_entry_version('US-FEDWIRE', DATE '2026-11-26', false, 'Thanksgiving',
                                         'published', 1, TIMESTAMPTZ '2026-01-01Z');
            SELECT publish_entry_version('US-FEDWIRE', DATE '2026-12-25', false, 'Christmas Day',
                                         'published', 2, TIMESTAMPTZ '2026-06-01Z');
            -- After as_of: the release must not see these.
            SELECT publish_entry_version('US-FEDWIRE', DATE '2026-11-26', true, 'Open after all',
                                         'published', 3, TIMESTAMPTZ '2026-09-01Z');
            SELECT publish_entry_version('US-FEDWIRE', DATE '2026-12-24', false, 'Christmas Eve',
                                         'published', 3, TIMESTAMPTZ '2026-09-01Z');
            """
        )
        export = PostgresReleaseStore(connection).export("2026.08.01.1", AS_OF)

    document = json.loads(export.content)
    jsonschema.validate(document, json.loads(RELEASE_SCHEMA.read_text(encoding="utf-8")))
    (fedwire,) = document["calendars"]
    assert [(e["date"], e["reason"], e["recorded_to"]) for e in fedwire["entries"]] == [
        ("2026-11-26", "Thanksgiving", None),
        ("2026-12-25", "Christmas", "2026-06-01T00:00:00+00:00"),
        ("2026-12-25", "Christmas Day", None),
    ]
    assert fedwire["revision"] == 2
    release = parse_release(export.content)
    assert not is_business_day(release, ["US-FEDWIRE"], THANKSGIVING)
    before_correction = datetime(2026, 3, 1, tzinfo=UTC)
    (version,) = [
        e for e in release.calendar("US-FEDWIRE").entries[CHRISTMAS] if e.recorded_at(AS_OF)
    ]
    assert version.reason == "Christmas Day"
    assert not is_business_day(release, ["US-FEDWIRE"], CHRISTMAS, as_of=before_correction)
