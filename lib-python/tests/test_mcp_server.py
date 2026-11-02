"""The local MCP server through the official SDK's client: in memory, and over stdio as an
MCP client runs it."""

import json
import os
import sys
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import anyio
import pytest
from mcp.client import Client
from mcp.client.stdio import StdioServerParameters
from mcp.types import CallToolResult, ListToolsResult, TextContent

from zeitwide import ZeitWide, load_release
from zeitwide.cli import main as cli_main
from zeitwide.mcp_server import API_KEY_VARIABLE, build_server, main
from zeitwide.mcp_server import _zeitwide as configured

GOLDEN = Path(__file__).parents[2] / "conformance" / "dataset.json"
HOSTED_TOOLS = {
    "is_business_day",
    "next_business_day",
    "add_business_days",
    "explain_date",
    "list_closures",
}
# What the hosted server's tools require (api/.../AnswerMcpTools.java).
HOSTED_REQUIRED = {
    "is_business_day": ["calendars", "date"],
    "next_business_day": ["calendars", "date"],
    "add_business_days": ["calendars", "date", "days"],
    "explain_date": ["calendar_id", "date"],
    "list_closures": ["calendar_id", "from", "to"],
}


def call(name: str, arguments: dict[str, Any], zw: ZeitWide | None = None) -> CallToolResult:
    async def run() -> CallToolResult:
        async with Client(build_server(zw or ZeitWide())) as client:
            return await client.call_tool(name, arguments)

    return anyio.run(run)


def test_the_tools_match_the_hosted_server() -> None:
    async def run() -> ListToolsResult:
        async with Client(build_server(ZeitWide())) as client:
            return await client.list_tools()

    tools = {tool.name: tool for tool in anyio.run(run).tools}

    assert set(tools) == HOSTED_TOOLS | {"verify_webhook"}
    for name, required in HOSTED_REQUIRED.items():
        assert sorted(tools[name].input_schema["required"]) == sorted(required)
        annotations = tools[name].annotations
        assert annotations is not None
        assert annotations.read_only_hint is True


def test_is_business_day_returns_the_api_json() -> None:
    result = call("is_business_day", {"calendars": ["US-FEDWIRE"], "date": "2026-11-26"})

    assert not result.is_error
    assert (
        result.structured_content
        == ZeitWide()
        .is_business_day("US-FEDWIRE", datetime(2026, 11, 26, tzinfo=UTC).date())
        .to_json()
    )


def test_shifts_explanations_and_closures() -> None:
    added = call(
        "add_business_days",
        {"calendars": ["GB-CHAPS"], "date": "2026-12-24", "days": 2, "combine": "any_open"},
    )
    explained = call("explain_date", {"calendar_id": "GB-CHAPS", "date": "2026-12-25"})
    closures = call(
        "list_closures", {"calendar_id": "GB-CHAPS", "from": "2026-12-01", "to": "2026-12-31"}
    )
    nxt = call("next_business_day", {"calendars": ["GB-CHAPS"], "date": "2026-12-24"})

    assert added.structured_content["date"] == "2026-12-30"
    assert explained.structured_content["calendars"][0]["reason"] == "Christmas Day"
    assert [c["date"] for c in closures.structured_content["closures"]] == [
        "2026-12-25",
        "2026-12-28",
    ]
    assert nxt.structured_content["skipped"][0]["date"] == "2026-12-25"


def test_as_of_is_passed_through() -> None:
    zw = ZeitWide(release=load_release(GOLDEN))
    arguments = {"calendars": ["GB-CHAPS"], "date": "2022-09-16", "days": 1}

    before = call("add_business_days", {**arguments, "as_of": "2022-09-09T12:00:00Z"}, zw)
    after = call("add_business_days", arguments, zw)

    assert (before.structured_content["date"], after.structured_content["date"]) == (
        "2022-09-19",
        "2022-09-20",
    )
    assert before.structured_content["as_of"] == "2022-09-09T12:00:00Z"


@pytest.mark.parametrize(
    ("name", "arguments", "message"),
    [
        ("is_business_day", {"calendars": ["US-FEDWIRE"], "date": "2030-01-02"}, "out_of_range"),
        ("explain_date", {"calendar_id": "XX-NOWHERE", "date": "2026-11-26"}, "unknown_calendar"),
        (
            "list_closures",
            {"calendar_id": "US-FEDWIRE", "from": "2026-12-01", "to": "2026-11-01"},
            "validation_failed",
        ),
        ("is_business_day", {"calendars": [], "date": "2026-11-26"}, "calendars"),
        (
            "add_business_days",
            {"calendars": ["US-FEDNOW"], "date": "2026-11-26", "days": 1001},
            "days",
        ),
        ("is_business_day", {"calendars": ["us-fedwire"], "date": "2026-11-26"}, "pattern"),
        (
            "is_business_day",
            {"calendars": ["US-FEDWIRE"], "date": "2026-11-26", "as_of": "2026-11-01T00:00:00"},
            "as_of",
        ),
    ],
)
def test_errors_are_tool_errors_the_model_can_read(
    name: str, arguments: dict[str, Any], message: str
) -> None:
    result = call(name, arguments)

    assert result.is_error
    (content,) = result.content
    assert isinstance(content, TextContent)
    assert message in content.text


def test_verify_webhook() -> None:
    import hashlib  # noqa: PLC0415
    import hmac  # noqa: PLC0415
    import time  # noqa: PLC0415

    body = json.dumps({"id": "evt_1", "type": "closure_added", "data": {}})
    timestamp = int(time.time())
    digest = hmac.new(b"s3cret", f"{timestamp}.{body}".encode(), hashlib.sha256).hexdigest()
    arguments = {"body": body, "signature": f"t={timestamp},v1={digest}", "secret": "s3cret"}

    valid = call("verify_webhook", arguments)
    invalid = call("verify_webhook", {**arguments, "secret": "wrong"})

    assert valid.structured_content == {"valid": True, "event": json.loads(body)}
    assert invalid.structured_content["valid"] is False


def test_offline_needs_no_key_and_live_needs_one(monkeypatch: pytest.MonkeyPatch) -> None:
    monkeypatch.delenv(API_KEY_VARIABLE, raising=False)

    assert configured(offline=True, release=None, base_url="").offline
    assert configured(offline=True, release=GOLDEN, base_url="").release.id == "2026.10.29.0"
    with pytest.raises(ValueError, match=API_KEY_VARIABLE):
        configured(offline=False, release=None, base_url="https://api.test")
    with pytest.raises(ValueError, match="--offline"):
        configured(offline=False, release=GOLDEN, base_url="https://api.test")
    monkeypatch.setenv(API_KEY_VARIABLE, "zw_live_test")
    assert not configured(offline=False, release=None, base_url="https://api.test").offline


def test_main_reports_a_missing_key(
    monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    monkeypatch.delenv(API_KEY_VARIABLE, raising=False)

    with pytest.raises(SystemExit):
        main([])

    assert API_KEY_VARIABLE in capsys.readouterr().err


def test_zeitwide_mcp_subcommand_passes_its_arguments_on(
    capsys: pytest.CaptureFixture[str],
) -> None:
    with pytest.raises(SystemExit):
        cli_main(["mcp", "--help"])

    assert "--offline" in capsys.readouterr().out


def test_the_server_works_over_stdio_in_an_mcp_client() -> None:
    """Starts ``zeitwide-mcp --offline`` as a subprocess, the way an MCP client does."""
    server = StdioServerParameters(
        command=sys.executable,
        args=["-m", "zeitwide.mcp_server", "--offline"],
        env={key: value for key, value in os.environ.items() if key != API_KEY_VARIABLE},
    )

    async def run() -> tuple[ListToolsResult, CallToolResult]:
        async with Client(server) as client:
            tools = await client.list_tools()
            answer = await client.call_tool(
                "next_business_day", {"calendars": ["US-FEDWIRE", "EU-T2"], "date": "2026-11-25"}
            )
            return tools, answer

    tools, answer = anyio.run(run)

    assert {tool.name for tool in tools.tools} == HOSTED_TOOLS | {"verify_webhook"}
    assert answer.structured_content["date"] == "2026-11-27"
    assert answer.structured_content["skipped"][0]["date"] == "2026-11-26"
