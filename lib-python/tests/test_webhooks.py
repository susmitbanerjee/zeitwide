"""``zeitwide.webhooks.verify`` against headers made as the API's ``WebhookSignature`` does."""

import hashlib
import hmac
import json
from datetime import UTC, datetime, timedelta

import pytest

from zeitwide import webhooks
from zeitwide.webhooks import WebhookVerificationError

SECRET = "whsec_test_secret"  # noqa: S105 - a test value
NOW = datetime(2026, 11, 2, 12, 0, tzinfo=UTC)
EVENT = {
    "id": "evt_123",
    "type": "closure_added",
    "created_at": "2026-11-02T12:00:00Z",
    "data": {"calendar_id": "GB-CHAPS", "date": "2026-12-28"},
}
BODY = json.dumps(EVENT).encode()


def header(body: bytes = BODY, secret: str = SECRET, at: datetime = NOW) -> str:
    timestamp = int(at.timestamp())
    digest = hmac.new(secret.encode(), f"{timestamp}.".encode() + body, hashlib.sha256)
    return f"t={timestamp},v1={digest.hexdigest()}"


def test_a_signed_delivery_returns_its_event() -> None:
    assert webhooks.verify(BODY, header(), SECRET, now=NOW) == EVENT
    assert webhooks.verify(BODY.decode(), header(), SECRET, now=NOW) == EVENT


def test_any_matching_v1_signature_is_enough_during_rotation() -> None:
    old = header(secret="old")  # noqa: S106 - a test value
    rotating = f"{old},v1={header().split('v1=')[1]}"

    assert webhooks.verify(BODY, rotating, SECRET, now=NOW) == EVENT


@pytest.mark.parametrize(
    ("body", "signature", "secret"),
    [
        (BODY + b" ", header(), SECRET),
        (BODY, header(), "whsec_wrong"),
        (BODY, header(secret="whsec_wrong"), SECRET),  # noqa: S106 - a test value
    ],
)
def test_a_changed_body_or_wrong_secret_is_refused(
    body: bytes, signature: str, secret: str
) -> None:
    with pytest.raises(WebhookVerificationError, match="No signature matches"):
        webhooks.verify(body, signature, secret, now=NOW)


@pytest.mark.parametrize("value", ["", "v1=abc", "t=1", "t=x,v1=abc", "t=1,v2=abc"])
def test_malformed_headers_are_refused(value: str) -> None:
    with pytest.raises(WebhookVerificationError, match="Malformed"):
        webhooks.verify(BODY, value, SECRET, now=NOW)


def test_old_or_future_deliveries_are_refused_outside_the_tolerance() -> None:
    old = header(at=NOW - timedelta(minutes=6))
    future = header(at=NOW + timedelta(minutes=6))

    for signature in (old, future):
        with pytest.raises(WebhookVerificationError, match="outside the tolerance"):
            webhooks.verify(BODY, signature, SECRET, now=NOW)
    assert webhooks.verify(BODY, old, SECRET, now=NOW, tolerance=timedelta(minutes=10)) == EVENT


@pytest.mark.parametrize("body", [b"not json", b"[1, 2]"])
def test_a_signed_body_that_is_not_an_event_is_refused(body: bytes) -> None:
    with pytest.raises(WebhookVerificationError, match="JSON"):
        webhooks.verify(body, header(body), SECRET, now=NOW)


def test_now_needs_a_time_zone() -> None:
    with pytest.raises(ValueError, match="time zone"):
        webhooks.verify(BODY, header(), SECRET, now=datetime(2026, 11, 2))  # noqa: DTZ001


def test_the_system_clock_is_used_by_default() -> None:
    with pytest.raises(WebhookVerificationError, match="outside the tolerance"):
        webhooks.verify(BODY, header(), SECRET)
