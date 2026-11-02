"""Offline receipt checks, RFC 8785 canonical JSON, Ed25519 and ``zeitwide verify``.

Receipts here are signed with the ``cryptography`` package (a test-only dependency) the way
the API's ``ReceiptService`` signs them: Ed25519 over the canonical payload bytes.
"""

import base64
import hashlib
import json
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from pathlib import Path
from typing import Any

import pytest
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.asymmetric.ed25519 import Ed25519PrivateKey
from hypothesis import given, settings
from hypothesis import strategies as st

from zeitwide import KeyRing, Receipt, verify_receipt
from zeitwide import ed25519 as pure_ed25519
from zeitwide.cli import EXIT_INVALID, EXIT_USAGE, EXIT_VALID, main
from zeitwide.jcs import canonicalize
from zeitwide.receipts import KeyRingError, find_receipt

ISSUED_AT = "2026-11-02T10:15:30.123456Z"
PAYLOAD: dict[str, Any] = {
    "request": {
        "method": "POST",
        "path": "/v1/business-days/check",
        "body": {"calendars": ["US-FEDWIRE"], "date": "2026-11-26"},
    },
    "answer": {"date": "2026-11-26", "is_business_day": False, "combine": "all_open"},
    "dataset_release": "2026.10.29.1",
    "calendar_revisions": {"US-FEDWIRE": 1},
    "issued_at": ISSUED_AT,
}


class Signer:
    def __init__(self, key_id: str = "zw-test") -> None:
        self.key_id = key_id
        self.private = Ed25519PrivateKey.generate()

    def pem(self) -> str:
        return (
            self.private.public_key()
            .public_bytes(
                serialization.Encoding.PEM, serialization.PublicFormat.SubjectPublicKeyInfo
            )
            .decode()
        )

    def ring(
        self, valid_from: str = "2026-01-01T00:00:00Z", valid_to: str | None = None
    ) -> KeyRing:
        return KeyRing.parse(json.dumps({"keys": [self.entry(valid_from, valid_to)]}))

    def entry(self, valid_from: str, valid_to: str | None) -> dict[str, object]:
        return {
            "key_id": self.key_id,
            "public_key": self.pem(),
            "valid_from": valid_from,
            "valid_to": valid_to,
        }

    def sign(self, payload: Mapping[str, Any]) -> dict[str, Any]:
        canonical = canonicalize(payload)
        digest = hashlib.sha256(canonical).hexdigest()
        signature = base64.urlsafe_b64encode(self.private.sign(canonical)).rstrip(b"=").decode()
        return {
            "id": "rcpt_" + digest[:32],
            "payload": payload,
            "payload_hash": digest,
            "key_id": self.key_id,
            "signature": signature,
        }


@pytest.fixture
def signer() -> Signer:
    return Signer()


def test_a_signed_receipt_verifies(signer: Signer) -> None:
    result = verify_receipt(signer.sign(PAYLOAD), signer.ring())

    assert result
    assert result.key_id == "zw-test"
    assert result.issued_at == datetime(2026, 11, 2, 10, 15, 30, 123456, tzinfo=UTC)


def test_receipt_objects_from_answers_verify_too(signer: Signer) -> None:
    assert verify_receipt(Receipt.from_json(signer.sign(PAYLOAD)), signer.ring())


@pytest.mark.parametrize(
    ("tamper", "reason"),
    [
        (lambda r: r["payload"]["answer"].update(is_business_day=True), "does not match its hash"),
        (lambda r: r.update(payload_hash="0" * 64), "does not match its hash"),
        (lambda r: r.update(id="rcpt_" + "0" * 32), "ID does not match"),
        (lambda r: r.update(key_id="zw-other"), "unknown key"),
        (lambda r: r.update(signature="A" * 86), "signature does not match"),
        (lambda r: r.update(signature="short"), "malformed signature"),
        (lambda r: r.update(id="receipt"), "malformed receipt ID"),
        (lambda r: r["payload"].pop("issued_at"), "no issued_at"),
    ],
)
def test_any_change_makes_a_receipt_invalid(
    signer: Signer, tamper: Callable[[dict[str, Any]], object], reason: str
) -> None:
    receipt = json.loads(json.dumps(signer.sign(PAYLOAD)))
    tamper(receipt)

    result = verify_receipt(receipt, signer.ring())

    assert not result
    assert reason in result.reason


def test_a_resigned_tampered_payload_fails_on_the_signature(signer: Signer) -> None:
    receipt = signer.sign(PAYLOAD)
    forged = Signer().sign({**PAYLOAD, "answer": {"is_business_day": True}})
    receipt.update(id=forged["id"], payload=forged["payload"], payload_hash=forged["payload_hash"])

    assert verify_receipt(receipt, signer.ring()).reason == "the signature does not match"


def test_the_key_must_have_been_valid_when_the_receipt_was_issued(signer: Signer) -> None:
    receipt = signer.sign(PAYLOAD)

    assert not verify_receipt(receipt, signer.ring(valid_from="2026-11-03T00:00:00Z"))
    assert not verify_receipt(receipt, signer.ring(valid_to="2026-11-01T00:00:00Z"))
    # Both ends are inclusive, as in the API.
    assert verify_receipt(receipt, signer.ring(valid_from=ISSUED_AT, valid_to=ISSUED_AT))


def test_receipts_from_a_rotated_key_still_verify() -> None:
    old, new = Signer("zw-old"), Signer("zw-new")
    ring = KeyRing.parse(
        json.dumps(
            {
                "keys": [
                    old.entry("2026-01-01T00:00:00Z", "2026-11-01T00:00:00Z"),
                    new.entry("2026-11-01T00:00:00Z", None),
                ]
            }
        )
    )

    assert verify_receipt(old.sign({**PAYLOAD, "issued_at": "2026-10-01T00:00:00Z"}), ring)
    assert verify_receipt(new.sign(PAYLOAD), ring)
    assert not verify_receipt(old.sign(PAYLOAD), ring)


def test_the_bundled_key_ring_is_the_published_one() -> None:
    ring = KeyRing.bundled()

    assert {"sc-2026-10", "zw-2026-10"} <= set(ring.keys)
    assert all(len(key.key) == 32 for key in ring.keys.values())
    assert verify_receipt(Signer("zw-2026-10").sign(PAYLOAD)).reason == (
        "the signature does not match"
    )


@pytest.mark.parametrize(
    "content",
    [
        "not json",
        '{"keys": [{"key_id": "a"}]}',
        '{"keys": [{"key_id": "a", "public_key": "x", "valid_from": "2026-01-01T00:00:00Z"}]}',
        '{"keys": [{"key_id": "a", "public_key": "-----BEGIN PUBLIC KEY-----\\nAAAA\\n'
        '-----END PUBLIC KEY-----", "valid_from": "2026-01-01T00:00:00Z"}]}',
    ],
)
def test_malformed_key_rings_are_refused(content: str) -> None:
    with pytest.raises(KeyRingError):
        KeyRing.parse(content)


def test_a_key_id_twice_is_refused(signer: Signer) -> None:
    entry = signer.entry("2026-01-01T00:00:00Z", None)

    with pytest.raises(KeyRingError, match="twice"):
        KeyRing.parse(json.dumps({"keys": [entry, entry]}))


def test_find_receipt_accepts_a_receipt_or_an_answer(signer: Signer) -> None:
    receipt = signer.sign(PAYLOAD)

    assert find_receipt(receipt) is receipt
    assert find_receipt({"date": "2026-11-26", "receipt": receipt}) is receipt
    with pytest.raises(ValueError, match="No receipt"):
        find_receipt({"date": "2026-11-26"})


# RFC 8785


def test_jcs_sorts_keys_and_writes_numbers_as_ecmascript() -> None:
    # The same case as the API's ReceiptServiceTest.
    assert canonicalize({"z": 1.0, "a": 2}) == b'{"a":2,"z":1}'


@pytest.mark.parametrize(
    ("number", "text"),
    [
        (0.0, "0"),
        (-0.0, "0"),
        (1e21, "1e+21"),
        (1e20, "100000000000000000000"),
        (123.456, "123.456"),
        (0.000001, "0.000001"),
        (1e-7, "1e-7"),
        (-1.5e-9, "-1.5e-9"),
        (4.5e300, "4.5e+300"),
        (333333333.33333329, "333333333.3333333"),
        # Beyond 2**53, integers are written as the double they become, as ECMAScript does.
        (2**60, "1152921504606847000"),
    ],
)
def test_jcs_numbers(number: float, text: str) -> None:
    assert canonicalize(number) == text.encode()


def test_jcs_strings_and_key_order_by_utf16() -> None:
    value = {"\u20ac": 1, "\r": 2, "\ufb33": 3, "\U0001f600": 4, "1": 5, "\u0080": 6}

    assert canonicalize(value).decode() == (
        '{"\\r":2,"1":5,"\u0080":6,"\u20ac":1,"\U0001f600":4,"\ufb33":3}'
    )
    assert canonicalize('a"\\\b\u001f/\u007f') == b'"a\\"\\\\\\b\\u001f/\x7f"'


@pytest.mark.parametrize("value", [float("nan"), float("inf"), {1: "x"}, object()])
def test_jcs_refuses_what_is_not_i_json(value: object) -> None:
    with pytest.raises((TypeError, ValueError)):
        canonicalize(value)


# Ed25519


@settings(max_examples=25, deadline=None)
@given(message=st.binary(max_size=300))
def test_pure_ed25519_agrees_with_cryptography(message: bytes) -> None:
    private = Ed25519PrivateKey.generate()
    public = private.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    signature = private.sign(message)

    assert pure_ed25519.verify(public, message, signature)
    assert not pure_ed25519.verify(public, message + b"!", signature)
    flipped = bytes([signature[0] ^ 1]) + signature[1:]
    assert not pure_ed25519.verify(public, message, flipped)


def test_ed25519_rejects_bad_lengths_and_a_non_canonical_s() -> None:
    private = Ed25519PrivateKey.generate()
    public = private.public_key().public_bytes(
        serialization.Encoding.Raw, serialization.PublicFormat.Raw
    )
    signature = private.sign(b"m")
    order = 2**252 + 27742317777372353535851937790883648493
    s = int.from_bytes(signature[32:], "little") + order

    assert not pure_ed25519.verify(public[:31], b"m", signature)
    assert not pure_ed25519.verify(public, b"m", signature[:63])
    assert not pure_ed25519.verify(public, b"m", signature[:32] + s.to_bytes(32, "little"))
    assert not pure_ed25519.verify(b"\xff" * 32, b"m", signature)


# zeitwide verify


def test_cli_verifies_a_receipt_file(
    signer: Signer, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    receipt_file = tmp_path / "answer.json"
    receipt_file.write_text(json.dumps({"receipt": signer.sign(PAYLOAD)}), encoding="utf-8")
    keys_file = tmp_path / "keys.json"
    keys_file.write_text(
        json.dumps({"keys": [signer.entry("2026-01-01T00:00:00Z", None)]}), encoding="utf-8"
    )

    assert main(["verify", str(receipt_file), "--keys", str(keys_file)]) == EXIT_VALID
    assert "valid: rcpt_" in capsys.readouterr().out


def test_cli_rejects_a_receipt_from_an_unknown_key_with_the_bundled_ring(
    signer: Signer, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    receipt_file = tmp_path / "receipt.json"
    receipt_file.write_text(json.dumps(signer.sign(PAYLOAD)), encoding="utf-8")

    assert main(["verify", str(receipt_file)]) == EXIT_INVALID
    assert "INVALID: unknown key 'zw-test'" in capsys.readouterr().out


def test_cli_reads_stdin(
    signer: Signer, monkeypatch: pytest.MonkeyPatch, capsys: pytest.CaptureFixture[str]
) -> None:
    receipt = signer.sign({**PAYLOAD, "answer": {"x": 1}})
    receipt["payload"]["answer"]["x"] = 2
    monkeypatch.setattr("sys.stdin", _Stdin(json.dumps(receipt)))

    assert main(["verify", "-"]) == EXIT_INVALID
    assert "does not match its hash" in capsys.readouterr().out


@pytest.mark.parametrize("content", ["not json", '{"date": "2026-11-26"}'])
def test_cli_reports_unreadable_input(
    content: str, tmp_path: Path, capsys: pytest.CaptureFixture[str]
) -> None:
    path = tmp_path / "bad.json"
    path.write_text(content, encoding="utf-8")

    assert main(["verify", str(path)]) == EXIT_USAGE
    assert "cannot read the receipt" in capsys.readouterr().err


def test_cli_reports_a_bad_key_ring(tmp_path: Path, capsys: pytest.CaptureFixture[str]) -> None:
    receipt, keys = tmp_path / "r.json", tmp_path / "k.json"
    receipt.write_text(json.dumps(Signer().sign(PAYLOAD)), encoding="utf-8")
    keys.write_text("{}", encoding="utf-8")

    assert main(["verify", str(receipt), "--keys", str(keys)]) == EXIT_USAGE
    assert "cannot read the key ring" in capsys.readouterr().err


class _Stdin:
    def __init__(self, text: str) -> None:
        self._text = text

    def read(self) -> str:
        return self._text
