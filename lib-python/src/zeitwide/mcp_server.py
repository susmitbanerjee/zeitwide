"""The local MCP server: ``uvx zeitwide-mcp --offline``, or ``zeitwide mcp --offline``.

It serves the five answer tools of the hosted server (``/mcp``) under the same names and
arguments, returning the same JSON (offline, without a receipt), plus ``verify_webhook``.
With ``--offline`` it answers from the bundled release and never touches the network;
without it, it asks the hosted API with the key in ``ZEITWIDE_API_KEY``.
"""

import argparse
import datetime as dt
import inspect
import os
import sys
from collections.abc import Callable, Sequence
from pathlib import Path
from typing import Annotated, Any, Literal

from mcp.server.mcpserver import MCPServer
from mcp.server.mcpserver.exceptions import ToolError
from mcp.types import ToolAnnotations
from pydantic import AwareDatetime, Field, StringConstraints

from zeitwide import __version__, webhooks
from zeitwide.answers import MAX_CALENDARS, MAX_CLOSURE_RANGE_DAYS, MAX_DAYS
from zeitwide.client import DEFAULT_BASE_URL, ApiError
from zeitwide.core import ZeitWide
from zeitwide.engine import OutOfRangeError
from zeitwide.release import ReleaseError, UnknownCalendarError, load_release

API_KEY_VARIABLE = "ZEITWIDE_API_KEY"

CalendarId = Annotated[str, StringConstraints(pattern=r"^[A-Z0-9]+(-[A-Z0-9]+)*$", max_length=64)]
Calendars = Annotated[
    list[CalendarId],
    Field(description="Calendar IDs to check.", min_length=1, max_length=MAX_CALENDARS),
]
CombineRule = Annotated[
    Literal["all_open", "any_open"],
    Field(description="Optional combination rule; defaults to all_open."),
]
AsOf = Annotated[
    AwareDatetime | None,
    Field(description="Optional ISO-8601 offset date-time for historical evidence."),
]

INSTRUCTIONS = """\
Business-day answers for payment rails (Fedwire, FedACH, FedNow, T2, CHAPS) and public
holiday calendars, each with the entry or weekend rule that decided it and the official
sources behind it. Never guess a date: a date outside a calendar's published coverage is
out_of_range. Offline answers come from the bundled dataset release named in each answer."""

_READ_ONLY = ToolAnnotations(read_only_hint=True, idempotent_hint=True, open_world_hint=False)


def build_server(zeitwide: ZeitWide) -> MCPServer:
    """The MCP server, with every tool answering through ``zeitwide``."""
    source = "the bundled dataset release" if zeitwide.offline else "the hosted Zeitwide API"
    server = MCPServer(
        name="zeitwide",
        version=__version__,
        instructions=f"{INSTRUCTIONS}\nThis server answers from {source}.",
    )

    @server.tool(
        name="is_business_day",
        description=(
            "Use when an agent needs to decide if a date is a business day across one or "
            "more calendars. Returns the answer, each calendar's explanation and sources, "
            "the dataset release and calendar revisions."
        ),
        annotations=_READ_ONLY,
        structured_output=True,
    )
    def is_business_day(
        calendars: Calendars,
        date: Annotated[dt.date, Field(description="Date to check.")],
        combine: CombineRule = "all_open",
        as_of: AsOf = None,
    ) -> dict[str, Any]:
        return _answer(
            lambda: zeitwide.is_business_day(calendars, date, combine, as_of=as_of).to_json()
        )

    @server.tool(
        name="next_business_day",
        description=(
            "Use when an agent needs the first business day strictly after a start date. "
            "Returns the date with the skipped days and their reasons, sources, dataset "
            "release and revisions."
        ),
        annotations=_READ_ONLY,
        structured_output=True,
    )
    def next_business_day(
        calendars: Calendars,
        date: Annotated[dt.date, Field(description="Start date.")],
        combine: CombineRule = "all_open",
        as_of: AsOf = None,
    ) -> dict[str, Any]:
        return _answer(
            lambda: zeitwide.next_business_day(calendars, date, combine, as_of=as_of).to_json()
        )

    @server.tool(
        name="add_business_days",
        description=(
            "Use when an agent needs to move forward or backward by business days. The start "
            "date is not counted, and zero returns the start date. Returns the date with the "
            "skipped days and their reasons, sources, dataset release and revisions."
        ),
        annotations=_READ_ONLY,
        structured_output=True,
    )
    def add_business_days(
        calendars: Calendars,
        date: Annotated[dt.date, Field(description="Start date.")],
        days: Annotated[
            int,
            Field(
                description="Signed number of business days to add; limited to -1000 through 1000.",
                ge=-MAX_DAYS,
                le=MAX_DAYS,
            ),
        ],
        combine: CombineRule = "all_open",
        as_of: AsOf = None,
    ) -> dict[str, Any]:
        return _answer(
            lambda: zeitwide.add_business_days(
                calendars, date, days, combine, as_of=as_of
            ).to_json()
        )

    @server.tool(
        name="explain_date",
        description=(
            "Use when an agent needs the explanation for one calendar on one date. Returns "
            "the deciding entry (reason, basis, revision, sources) or the weekend rule, with "
            "the dataset release and revision."
        ),
        annotations=_READ_ONLY,
        structured_output=True,
    )
    def explain_date(
        calendar_id: Annotated[CalendarId, Field(description="Calendar ID.")],
        date: Annotated[dt.date, Field(description="Date to explain.")],
        as_of: AsOf = None,
    ) -> dict[str, Any]:
        return _answer(lambda: zeitwide.explain_date(calendar_id, date, as_of=as_of).to_json())

    # The hosted tool's argument is "from", a Python keyword. The SDK builds the schema
    # from this signature and then calls the tool with each field's alias as the keyword,
    # so the function takes keyword arguments and borrows the signature (set before the
    # tool is registered, since decorators apply from the bottom up).
    @server.tool(
        name="list_closures",
        description=(
            "Use when an agent needs source-backed closures for one calendar over an "
            f"inclusive date range. The range can cover at most {MAX_CLOSURE_RANGE_DAYS} "
            "days. Returns the closures with their sources, dataset release and revision."
        ),
        annotations=_READ_ONLY,
        structured_output=True,
    )
    @_signature(_list_closures_arguments)
    def list_closures(**arguments: Any) -> dict[str, Any]:  # noqa: ANN401 - validated by the SDK
        return _answer(
            lambda: zeitwide.list_closures(
                arguments["calendar_id"],
                arguments["from"],
                arguments["to"],
                as_of=arguments.get("as_of"),
            ).to_json()
        )

    @server.tool(
        name="verify_webhook",
        description=(
            "Use when an agent receives a Zeitwide webhook delivery and must check that "
            "Zeitwide sent it before acting on it. Pass the raw body, the Zeitwide-Signature "
            "header and the webhook's signing secret. Returns whether it is valid and, if so, "
            "the event."
        ),
        annotations=ToolAnnotations(read_only_hint=True, open_world_hint=False),
        structured_output=True,
    )
    def verify_webhook(
        body: Annotated[str, Field(description="The request body exactly as received.")],
        signature: Annotated[str, Field(description="The Zeitwide-Signature header value.")],
        secret: Annotated[str, Field(description="The webhook's signing secret.")],
        tolerance_seconds: Annotated[
            int,
            Field(
                description="How old the signature may be, in seconds; defaults to 300.",
                ge=0,
            ),
        ] = 300,
    ) -> dict[str, Any]:
        try:
            event = webhooks.verify(
                body, signature, secret, tolerance=dt.timedelta(seconds=tolerance_seconds)
            )
        except webhooks.WebhookVerificationError as error:
            return {"valid": False, "reason": str(error)}
        return {"valid": True, "event": event}

    return server


def _signature[F: Callable[..., Any]](model: Callable[..., Any]) -> Callable[[F], F]:
    def borrow(function: F) -> F:
        function.__signature__ = inspect.signature(model)  # type: ignore[attr-defined]
        return function

    return borrow


def _list_closures_arguments(
    calendar_id: Annotated[CalendarId, Field(description="Calendar ID.")],
    from_: Annotated[dt.date, Field(alias="from", description="Inclusive first date.")],
    to: Annotated[dt.date, Field(description="Inclusive last date.")],
    as_of: AsOf = None,
) -> dict[str, Any]:
    raise NotImplementedError  # only its signature is used


def _answer(ask: Callable[[], dict[str, Any]]) -> dict[str, Any]:
    """Runs one question, turning the errors a caller can act on into tool errors."""
    try:
        return ask()
    except OutOfRangeError as error:
        raise ToolError(f"out_of_range: {error}") from error
    except UnknownCalendarError as error:
        raise ToolError(f"unknown_calendar: {error}") from error
    except ApiError as error:
        raise ToolError(f"{error.code}: {error}") from error
    except ValueError as error:
        raise ToolError(f"validation_failed: {error}") from error


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        prog="zeitwide-mcp",
        description="Zeitwide's local MCP server over stdio.",
    )
    parser.add_argument(
        "--offline",
        action="store_true",
        help="answer from the bundled dataset release, with no network",
    )
    parser.add_argument(
        "--release",
        type=Path,
        help="with --offline, a dataset release file to answer from instead of the bundled one",
    )
    parser.add_argument(
        "--base-url",
        default=DEFAULT_BASE_URL,
        help=f"the hosted API, without --offline (default {DEFAULT_BASE_URL})",
    )
    parser.add_argument("--version", action="version", version=f"zeitwide-mcp {__version__}")
    args = parser.parse_args(argv)
    try:
        zeitwide = _zeitwide(args.offline, args.release, args.base_url)
    except (OSError, ReleaseError, ValueError) as error:
        parser.error(str(error))
    with zeitwide:
        build_server(zeitwide).run("stdio")
    return 0


def _zeitwide(offline: bool, release: Path | None, base_url: str) -> ZeitWide:
    if offline:
        return ZeitWide(release=None if release is None else load_release(release))
    if release is not None:
        raise ValueError("--release needs --offline")
    key = os.environ.get(API_KEY_VARIABLE)
    if not key:
        raise ValueError(f"Pass --offline, or set {API_KEY_VARIABLE} to ask the hosted API")
    return ZeitWide(key, base_url=base_url)


if __name__ == "__main__":
    sys.exit(main())
