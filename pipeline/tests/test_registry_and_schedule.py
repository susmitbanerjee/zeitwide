from datetime import timedelta
from pathlib import Path

import pytest

from settlecal_pipeline.db import RegisteredSource, SourceState
from settlecal_pipeline.fetch import Validators
from settlecal_pipeline.jobs import due_sources
from settlecal_pipeline.schedule import is_due, iso_duration
from settlecal_pipeline.sources import SourceRegistry, UnknownSourceError, builtin_registry, catalog
from support.doubles import START
from support.fixture_source import HolidayTableParser, HolidayTableSource

SOURCE = HolidayTableSource(
    id="fixture-holidays",
    url="https://fixtures.example.test/holidays",
    parser=HolidayTableParser("US-FEDWIRE"),
)


def state(last_checked_hours_ago: float | None = None, *, suspended: bool = False) -> SourceState:
    checked = (
        None if last_checked_hours_ago is None else START - timedelta(hours=last_checked_hours_ago)
    )
    return SourceState(Validators(), None, checked, 0, START if suspended else None)


def test_registry_returns_registered_source() -> None:
    assert SourceRegistry([SOURCE]).get("fixture-holidays") is SOURCE


def test_registry_rejects_duplicate_ids() -> None:
    with pytest.raises(ValueError, match="registered twice"):
        SourceRegistry([SOURCE, SOURCE])


def test_registry_rejects_unknown_id() -> None:
    with pytest.raises(UnknownSourceError):
        SourceRegistry().get("nope")


def test_builtin_registry_loads_each_catalog_module(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    (tmp_path / "example_source.py").write_text(
        "from support.fixture_source import HolidayTableParser, HolidayTableSource\n"
        "SOURCE = HolidayTableSource('example', 'https://e.test/', HolidayTableParser('US-X'))\n",
        encoding="utf-8",
    )
    monkeypatch.setattr(catalog, "__path__", [str(tmp_path)])

    assert [source.id for source in builtin_registry()] == ["example"]


def test_builtin_registry_rejects_module_without_source(
    tmp_path: Path, monkeypatch: pytest.MonkeyPatch
) -> None:
    (tmp_path / "half_written.py").write_text("PARSER = None\n", encoding="utf-8")
    monkeypatch.setattr(catalog, "__path__", [str(tmp_path)])

    with pytest.raises(ValueError, match="defines no SOURCE"):
        builtin_registry()


def test_shipped_catalog_loads() -> None:
    assert isinstance(builtin_registry(), SourceRegistry)


@pytest.mark.parametrize(
    ("every", "expected"),
    [
        (timedelta(days=1), "P1D"),
        (timedelta(hours=1), "PT1H"),
        (timedelta(minutes=15), "PT15M"),
        (timedelta(days=1, hours=2, minutes=3, seconds=4), "P1DT2H3M4S"),
    ],
)
def test_iso_duration(every: timedelta, expected: str) -> None:
    assert iso_duration(every) == expected


@pytest.mark.parametrize("every", [timedelta(0), timedelta(milliseconds=1500)])
def test_iso_duration_rejects_non_positive_or_fractional(every: timedelta) -> None:
    with pytest.raises(ValueError, match="positive whole number"):
        iso_duration(every)


def test_never_checked_source_is_due() -> None:
    assert is_due(None, timedelta(days=1), START)


def test_source_is_due_once_its_schedule_passes() -> None:
    assert is_due(START - timedelta(days=1), timedelta(days=1), START)
    assert not is_due(START - timedelta(hours=23), timedelta(days=1), START)


def test_due_sources_skip_disabled_suspended_and_recent() -> None:
    sources = [
        SOURCE,
        HolidayTableSource("disabled", "https://d.test/", HolidayTableParser("US-X")),
        HolidayTableSource("suspended", "https://s.test/", HolidayTableParser("US-X")),
        HolidayTableSource("recent", "https://r.test/", HolidayTableParser("US-X")),
        HolidayTableSource("unregistered", "https://u.test/", HolidayTableParser("US-X")),
    ]
    registered = {
        "fixture-holidays": RegisteredSource(1, enabled=True),
        "disabled": RegisteredSource(2, enabled=False),
        "suspended": RegisteredSource(3, enabled=True),
        "recent": RegisteredSource(4, enabled=True),
    }
    states = {
        "fixture-holidays": state(25),
        "suspended": state(48, suspended=True),
        "recent": state(1),
    }

    assert due_sources(sources, registered, states, START) == ["fixture-holidays"]
