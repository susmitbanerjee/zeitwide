import json

import pytest
import structlog
from opentelemetry.sdk.trace import TracerProvider
from pydantic import ValidationError

from zeitwide_pipeline.logs import configure_logging
from zeitwide_pipeline.settings import Settings
from zeitwide_pipeline.telemetry import configure_telemetry


@pytest.fixture(autouse=True)
def _reset_structlog() -> None:
    structlog.reset_defaults()


def required_env(
    monkeypatch: pytest.MonkeyPatch, contact: str = "https://zeitwide.example/bot"
) -> None:
    monkeypatch.setenv("ZEITWIDE_SNAPSHOT_BUCKET", "zeitwide-snapshots")
    monkeypatch.setenv("ZEITWIDE_BOT_CONTACT_URL", contact)


def test_settings_read_environment(monkeypatch: pytest.MonkeyPatch) -> None:
    required_env(monkeypatch)
    monkeypatch.setenv("OTEL_EXPORTER_OTLP_ENDPOINT", "https://otlp.example")

    settings = Settings()  # type: ignore[call-arg]

    assert (settings.snapshot_bucket, settings.db_pool_max_size, settings.otlp_endpoint) == (
        "zeitwide-snapshots",
        2,
        "https://otlp.example",
    )


def test_settings_require_https_contact_url(monkeypatch: pytest.MonkeyPatch) -> None:
    required_env(monkeypatch, contact="http://zeitwide.example/bot")

    with pytest.raises(ValidationError, match="https"):
        Settings()  # type: ignore[call-arg]


def test_settings_cap_the_connection_pool(monkeypatch: pytest.MonkeyPatch) -> None:
    required_env(monkeypatch)
    monkeypatch.setenv("ZEITWIDE_DB_POOL_MAX_SIZE", "20")

    with pytest.raises(ValidationError):
        Settings()  # type: ignore[call-arg]


def test_logs_are_json_with_severity_and_trace(capsys: pytest.CaptureFixture[str]) -> None:
    configure_logging()
    tracer = TracerProvider().get_tracer("test")

    with tracer.start_as_current_span("step"):
        structlog.get_logger().warning("source_check_failed", source_id="fixture")

    line = json.loads(capsys.readouterr().out)
    assert (line["event"], line["severity"], line["source_id"], len(line["trace_id"])) == (
        "source_check_failed",
        "WARNING",
        "fixture",
        32,
    )


def test_telemetry_without_export_still_records() -> None:
    telemetry, shutdown = configure_telemetry(export=False)

    with telemetry.tracer.start_as_current_span("step"):
        telemetry.parse_failures.add(1, {"zeitwide.source": "fixture"})
    shutdown()
