"""structlog JSON logs on stdout, in the shape Cloud Logging and Grafana expect."""

import logging
import sys

import structlog
from opentelemetry import trace
from structlog.typing import EventDict, WrappedLogger

_SEVERITY = {"critical": "CRITICAL", "exception": "ERROR", "warn": "WARNING"}


def _add_severity(_logger: WrappedLogger, method_name: str, event_dict: EventDict) -> EventDict:
    """Cloud Logging reads ``severity``."""
    event_dict["severity"] = _SEVERITY.get(method_name, method_name.upper())
    return event_dict


def _add_trace_context(
    _logger: WrappedLogger, _method_name: str, event_dict: EventDict
) -> EventDict:
    """Links each line to its trace in Grafana."""
    context = trace.get_current_span().get_span_context()
    if context.is_valid:
        event_dict["trace_id"] = format(context.trace_id, "032x")
        event_dict["span_id"] = format(context.span_id, "016x")
    return event_dict


class _CurrentStdout:
    """Writes to whatever ``sys.stdout`` is now, not what it was when logging was set up.

    Loggers are cached on first use, so a stream captured at setup (a test runner's, say)
    would otherwise outlive itself.
    """

    def write(self, text: str) -> int:
        return sys.stdout.write(text)

    def flush(self) -> None:
        sys.stdout.flush()


def configure_logging(level: int = logging.INFO) -> None:
    # Libraries (Procrastinate, httpx) log through the standard library; keep their
    # warnings and errors on stderr.
    logging.basicConfig(level=logging.WARNING, stream=sys.stderr)
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            _add_severity,
            _add_trace_context,
            structlog.processors.TimeStamper(fmt="iso", utc=True),
            structlog.processors.format_exc_info,
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(level),
        logger_factory=structlog.WriteLoggerFactory(file=_CurrentStdout()),  # type: ignore[arg-type]
        cache_logger_on_first_use=True,
    )
