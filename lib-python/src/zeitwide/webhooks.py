"""Checking that a webhook delivery came from Zeitwide.

Each delivery carries ``Zeitwide-Signature: t=<unix seconds>,v1=<hex>``, where ``v1`` is
the HMAC-SHA256 of ``<t>.<raw body>`` under the webhook's signing secret. Verify the raw
bytes as received, before any JSON parsing, and deduplicate retries by the event ``id``.
"""

import hashlib
import hmac
import json
from datetime import UTC, datetime, timedelta
from typing import Any

SIGNATURE_HEADER = "Zeitwide-Signature"
DEFAULT_TOLERANCE = timedelta(minutes=5)


class WebhookVerificationError(ValueError):
    """The delivery is not signed by this secret, or is too old to trust."""


def verify(
    body: bytes | str,
    header: str,
    secret: str,
    *,
    tolerance: timedelta = DEFAULT_TOLERANCE,
    now: datetime | None = None,
) -> dict[str, Any]:
    """Checks a delivery's signature and age, then returns its event.

    Args:
        body: The request body exactly as received (str is encoded as UTF-8).
        header: The ``Zeitwide-Signature`` header.
        secret: The webhook's signing secret, shown once when it was created.
        tolerance: How far the signed time may be from ``now``, to refuse replays.
        now: The current time, for tests; the system clock in UTC by default.

    Returns:
        The event (``id``, ``type``, ``created_at`` and ``data``), parsed only once verified.

    Raises:
        WebhookVerificationError: The header is malformed, no signature matches, or the
            signed time is outside ``tolerance``.
    """
    if now is not None and now.tzinfo is None:
        raise ValueError("now needs a time zone")
    raw = body.encode("utf-8") if isinstance(body, str) else body
    timestamp, signatures = _parse(header)
    signed = str(timestamp).encode("ascii") + b"." + raw
    expected = hmac.new(secret.encode("utf-8"), signed, hashlib.sha256).hexdigest()
    # Several v1 values may come while a secret is being rotated: any one may match.
    if not any(hmac.compare_digest(expected, signature) for signature in signatures):
        raise WebhookVerificationError("No signature matches this secret")
    moment = datetime.now(UTC) if now is None else now
    signed_at = datetime.fromtimestamp(timestamp, UTC)
    if abs(moment - signed_at) > tolerance:
        raise WebhookVerificationError(f"Signed at {signed_at.isoformat()}, outside the tolerance")
    try:
        event = json.loads(raw)
    except ValueError as error:
        raise WebhookVerificationError("The signed body is not JSON") from error
    if not isinstance(event, dict):
        raise WebhookVerificationError("The signed body is not a JSON object")
    return event


def _parse(header: str) -> tuple[int, list[str]]:
    timestamp: int | None = None
    signatures: list[str] = []
    for part in header.split(","):
        name, _, value = part.strip().partition("=")
        if name == "t" and value.isdigit():
            timestamp = int(value)
        elif name == "v1" and value:
            signatures.append(value)
    if timestamp is None or not signatures:
        raise WebhookVerificationError(f"Malformed {SIGNATURE_HEADER} header")
    return timestamp, signatures
