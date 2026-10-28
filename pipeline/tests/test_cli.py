from typer.testing import CliRunner

from settlecal_pipeline.cli import app

CLI_USAGE_ERROR = 2


def test_health_reports_ok() -> None:
    result = CliRunner().invoke(app, ["health"])

    assert (result.exit_code, result.output.strip()) == (0, "ok")


def test_unknown_command_is_rejected() -> None:
    assert CliRunner().invoke(app, ["no-such-command"]).exit_code == CLI_USAGE_ERROR


def test_review_is_a_command_group() -> None:
    result = CliRunner().invoke(app, ["review", "--help"])

    assert result.exit_code == 0
    assert all(command in result.output for command in ("list", "show", "approve", "reject"))
