"""Every shipped source against its real snapshots, plus the rail rules it must respect."""

from datetime import date

import pytest

from settlecal_pipeline.calendars import load_catalog
from settlecal_pipeline.models import Observation
from settlecal_pipeline.sources import ParseError, builtin_registry, parse_snapshot
from settlecal_pipeline.sources.catalog import (
    bundesbank_holidays,
    ecb_public_holidays,
    federalreserve_k8,
    frbservices_holiday_schedules,
    govuk_bank_holidays,
    opm_federal_holidays,
)
from settlecal_pipeline.sources.pdf_text import pdf_lines
from support.goldens import Golden, goldens

REGISTRY = builtin_registry()
GOLDENS = goldens({source.id for source in REGISTRY})
MIN_SNAPSHOTS_PER_SOURCE = 2


def parse(golden: Golden) -> list[Observation]:
    return parse_snapshot(REGISTRY.get(golden.source_id).parser, golden.content, golden.meta)


def golden_named(source_id: str, prefix: str) -> Golden:
    return next(g for g in GOLDENS if g.source_id == source_id and g.name.startswith(prefix))


def day(observations: list[Observation], calendar_id: str, on: date) -> list[Observation]:
    return [o for o in observations if o.calendar_id == calendar_id and o.entry_date == on]


@pytest.mark.parametrize("golden", GOLDENS, ids=lambda g: f"{g.source_id}/{g.name}")
def test_parser_matches_golden_observations(golden: Golden) -> None:
    assert parse(golden) == golden.expected()


@pytest.mark.parametrize("source", list(REGISTRY), ids=lambda s: s.id)
def test_every_source_has_two_real_snapshots(source: object) -> None:
    snapshots = [g for g in GOLDENS if g.source_id == getattr(source, "id", None)]
    assert len(snapshots) >= MIN_SNAPSHOTS_PER_SOURCE


@pytest.mark.parametrize("golden", GOLDENS, ids=lambda g: f"{g.source_id}/{g.name}")
def test_parsers_emit_only_catalog_calendars(golden: Golden) -> None:
    assert {o.calendar_id for o in parse(golden)} <= load_catalog().keys()


@pytest.mark.parametrize("golden", GOLDENS, ids=lambda g: f"{g.source_id}/{g.name}")
def test_truncated_snapshot_is_rejected(golden: Golden) -> None:
    with pytest.raises(ParseError):
        parse_snapshot(
            REGISTRY.get(golden.source_id).parser,
            golden.content[: len(golden.content) // 3],
            golden.meta,
        )


class TestFederalReserve:
    live = golden_named(frbservices_holiday_schedules.SOURCE.id, "live")

    def test_saturday_holiday_keeps_the_friday_open(self) -> None:
        observations = parse(self.live)

        assert day(observations, "US-FEDWIRE", date(2026, 7, 3)) == []
        assert [o.is_open for o in day(observations, "US-FEDWIRE", date(2026, 7, 4))] == [False]

    def test_sunday_holiday_closes_the_monday(self) -> None:
        observations = parse(self.live)

        assert day(observations, "US-FEDACH", date(2027, 7, 5)) == [
            Observation("US-FEDACH", date(2027, 7, 5), False, "Independence Day (observed)")
        ]

    def test_fednow_is_open_on_every_fed_holiday(self) -> None:
        fednow = [o for o in parse(self.live) if o.calendar_id == "US-FEDNOW"]

        assert fednow
        assert all(o.is_open for o in fednow)

    def test_k8_corroborates_the_same_dates(self) -> None:
        board = golden_named(federalreserve_k8.SOURCE.id, "live")

        assert {(o.calendar_id, o.entry_date, o.is_open) for o in parse(board)} == {
            (o.calendar_id, o.entry_date, o.is_open) for o in parse(self.live)
        }

    def test_k8_footnoted_weekday_is_still_a_closure(self) -> None:
        archived = parse(golden_named(federalreserve_k8.SOURCE.id, "archive"))

        assert [o.is_open for o in day(archived, "US-FEDWIRE", date(2025, 1, 20))] == [False]

    def test_marker_on_the_wrong_weekday_is_rejected(self) -> None:
        tampered = self.live.content.replace(b">November 26<", b">November 26*<", 1)

        with pytest.raises(ParseError, match="falls on Thursday"):
            parse_snapshot(frbservices_holiday_schedules.SOURCE.parser, tampered, self.live.meta)

    def test_missing_observance_footnote_is_rejected(self) -> None:
        tampered = self.live.content.replace(b"falling on Saturday", b"falling on weekends")

        with pytest.raises(ParseError, match="Observance footnote"):
            parse_snapshot(frbservices_holiday_schedules.SOURCE.parser, tampered, self.live.meta)


class TestEcb:
    live = golden_named(ecb_public_holidays.SOURCE.id, "live")

    def test_target_closing_days_close_t2(self) -> None:
        closed = {
            o.entry_date for o in parse(self.live) if not o.is_open and o.entry_date.year == 2026
        }

        assert closed == {
            date(2026, 1, 1),
            date(2026, 4, 3),
            date(2026, 4, 6),
            date(2026, 5, 1),
            date(2026, 12, 25),
            date(2026, 12, 26),
        }

    def test_other_ecb_weekday_holidays_leave_t2_open(self) -> None:
        assert [o.is_open for o in day(parse(self.live), "EU-T2", date(2026, 5, 14))] == [True]

    def test_ecb_holidays_on_weekends_say_nothing(self) -> None:
        assert day(parse(self.live), "EU-T2", date(2026, 5, 9)) == []

    def test_missing_legend_is_rejected(self) -> None:
        tampered = self.live.content.replace(b"TARGET closing day", b"closing day")

        with pytest.raises(ParseError, match="Legend"):
            parse_snapshot(ecb_public_holidays.SOURCE.parser, tampered, self.live.meta)


class TestGovUk:
    live = golden_named(govuk_bank_holidays.SOURCE.id, "live")

    def test_substitute_days_close_chaps_and_england_and_wales(self) -> None:
        observations = parse(self.live)

        assert {o.calendar_id for o in observations if o.entry_date == date(2026, 12, 28)} == {
            "GB-EAW",
            "GB-CHAPS",
        }

    def test_scotland_is_ignored(self) -> None:
        # St Andrew's Day is a Scottish bank holiday only.
        assert day(parse(self.live), "GB-EAW", date(2026, 11, 30)) == []

    def test_missing_division_is_rejected(self) -> None:
        with pytest.raises(ParseError, match="england-and-wales"):
            parse_snapshot(govuk_bank_holidays.SOURCE.parser, b'{"scotland": {}}', self.live.meta)


class TestOpm:
    live = golden_named(opm_federal_holidays.SOURCE.id, "live")

    def test_saturday_holiday_is_observed_on_friday(self) -> None:
        assert [o.reason for o in day(parse(self.live), "US-FEDERAL", date(2026, 7, 3))] == [
            "Independence Day"
        ]

    def test_new_years_day_on_saturday_is_observed_the_year_before(self) -> None:
        assert [o.reason for o in day(parse(self.live), "US-FEDERAL", date(2027, 12, 31))] == [
            "New Year's Day"
        ]

    def test_inauguration_day_is_not_a_nationwide_holiday(self) -> None:
        archived = parse(golden_named(opm_federal_holidays.SOURCE.id, "archive"))

        assert [o.reason for o in day(archived, "US-FEDERAL", date(2025, 1, 20))] == [
            "Birthday of Martin Luther King, Jr."
        ]

    def test_wrong_weekday_is_rejected(self) -> None:
        tampered = self.live.content.replace(b"Thursday, November 26", b"Friday, November 26", 1)

        with pytest.raises(ParseError, match="Friday"):
            parse_snapshot(opm_federal_holidays.SOURCE.parser, tampered, self.live.meta)


class TestBundesbank:
    live = golden_named(bundesbank_holidays.SOURCE.id, "live")
    archive = golden_named(bundesbank_holidays.SOURCE.id, "archive")

    def test_target_holidays_match_the_ecb(self) -> None:
        bundesbank = {o.entry_date for o in parse(self.archive) if o.calendar_id == "EU-T2"}
        ecb = {
            o.entry_date
            for o in parse(golden_named(ecb_public_holidays.SOURCE.id, "live"))
            if not o.is_open and o.entry_date.year == 2026
        }

        assert bundesbank == ecb

    def test_only_nationwide_holidays_close_germany(self) -> None:
        germany = {o.entry_date for o in parse(self.live) if o.calendar_id == "DE"}

        assert date(2027, 5, 6) in germany  # Ascension Day, nationwide
        assert date(2027, 1, 6) not in germany  # Epiphany, only three states

    def test_finds_the_one_pdf_link_on_the_stable_page(self) -> None:
        page = b'<a href="/resource/blob/764518/abc/x/feiertage-in-deutschland-1-data.pdf">PDF</a>'

        assert (
            bundesbank_holidays.BundesbankPdfLink().find(
                page, "https://www.bundesbank.de/en/tasks/payment-systems/target/x-764518"
            )
            == "https://www.bundesbank.de/resource/blob/764518/abc/x/feiertage-in-deutschland-1-data.pdf"
        )

    def test_page_without_the_pdf_link_is_rejected(self) -> None:
        with pytest.raises(ParseError, match="one holiday PDF link"):
            bundesbank_holidays.BundesbankPdfLink().find(
                b"<p>Moved</p>", "https://www.bundesbank.de/"
            )

    def test_pdf_with_too_many_pages_is_rejected(self) -> None:
        with pytest.raises(ParseError, match="at most 1"):
            pdf_lines(self.live.content, self.live.meta.url, max_pages=1)

    def test_corrupt_pdf_is_rejected(self) -> None:
        with pytest.raises(ParseError, match="not a readable PDF"):
            parse_snapshot(bundesbank_holidays.SOURCE.parser, b"%PDF-1.4 garbage", self.live.meta)
