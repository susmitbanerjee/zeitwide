"""Checking a Zeitwide receipt offline, exactly as ``POST /v1/receipts/verify`` does.

A receipt signs the RFC 8785 canonical JSON of ``{request, answer, dataset_release,
calendar_revisions, issued_at}`` with Ed25519. Its ID and ``payload_hash`` come from the
SHA-256 of those bytes, and the key that signed it must have been valid at ``issued_at``.
The public keys come from ``/.well-known/zeitwide-keys.json``; a copy ships with the library.
"""

import base64
import binascii
import hashlib
import hmac
import json
import re
from collections.abc import Mapping
from dataclasses import dataclass
from datetime import datetime
from typing import Any

from zeitwide import bundled, ed25519
from zeitwide.answers import Receipt
from zeitwide.jcs import canonicalize

_RECEIPT_ID = re.compile(r"rcpt_[0-9a-f]{32}")
_PAYLOAD_HASH = re.compile(r"[0-9a-f]{64}")
_SIGNATURE = re.compile(r"[A-Za-z0-9_-]{86}")
_ID_HASH_LENGTH = 32
# The DER prefix of an Ed25519 SubjectPublicKeyInfo: the algorithm, then a 32-byte key.
_SPKI_PREFIX = bytes.fromhex("302a300506032b6570032100")
_PAYLOAD_FIELDS = ("request", "answer", "dataset_release", "calendar_revisions", "issued_at")


class KeyRingError(ValueError):
    """``zeitwide-keys.json`` isn't in the published format."""


@dataclass(frozen=True, slots=True)
class PublicKey:
    key_id: str
    key: bytes
    valid_from: datetime
    # None while the key is still the active one.
    valid_to: datetime | None

    def valid_at(self, moment: datetime) -> bool:
        return self.valid_from <= moment and (self.valid_to is None or moment <= self.valid_to)


@dataclass(frozen=True, slots=True)
class KeyRing:
    keys: Mapping[str, PublicKey]

    @classmethod
    def parse(cls, content: bytes | str) -> "KeyRing":
        """Reads the ``/.well-known/zeitwide-keys.json`` format."""
        try:
            raw = json.loads(content)
            keys = [_public_key(item) for item in raw["keys"]]
        except (KeyError, TypeError, ValueError) as error:
            if isinstance(error, KeyRingError):
                raise
            raise KeyRingError(f"Malformed key ring: {error}") from error
        by_id = {key.key_id: key for key in keys}
        if len(by_id) != len(keys):
            raise KeyRingError("The key ring has a key ID twice")
        return cls(by_id)

    @classmethod
    def bundled(cls) -> "KeyRing":
        """The keys published when this version of the library was built."""
        return cls.parse(bundled.receipt_keys())


@dataclass(frozen=True, slots=True)
class Verification:
    valid: bool
    # Why not, when it isn't valid; empty when it is.
    reason: str
    receipt_id: str | None = None
    key_id: str | None = None
    issued_at: datetime | None = None

    def __bool__(self) -> bool:
        return self.valid


def verify_receipt(
    receipt: Receipt | Mapping[str, Any], keys: KeyRing | None = None
) -> Verification:
    """Checks a receipt's hash, ID and signature, and that its key was valid when it was issued.

    Args:
        receipt: A ``Receipt``, or its JSON as the API returned it.
        keys: The public keys to trust; the bundled copy of ``zeitwide-keys.json`` by default.
            Receipts signed by keys published after this library was built need a newer ring.

    Returns:
        A ``Verification`` that is truthy only when the receipt is valid.
    """
    ring = KeyRing.bundled() if keys is None else keys
    try:
        parsed = receipt if isinstance(receipt, Receipt) else Receipt.from_json(receipt)
    except (KeyError, TypeError) as error:
        return Verification(valid=False, reason=f"not a receipt: {error}")
    return _verify(parsed, ring)


def _verify(receipt: Receipt, ring: KeyRing) -> Verification:  # noqa: PLR0911 - one per check
    invalid = _shape_problem(receipt)
    if invalid:
        return Verification(valid=False, reason=invalid, receipt_id=receipt.id)
    try:
        canonical = canonicalize(receipt.payload)
        issued_at = datetime.fromisoformat(str(receipt.payload["issued_at"]))
    except (TypeError, ValueError) as error:
        return Verification(
            valid=False, reason=f"malformed payload: {error}", receipt_id=receipt.id
        )
    if issued_at.tzinfo is None:
        return Verification(valid=False, reason="issued_at has no time zone", receipt_id=receipt.id)

    def result(reason: str = "") -> Verification:
        return Verification(not reason, reason, receipt.id, receipt.key_id, issued_at)

    digest = hashlib.sha256(canonical).hexdigest()
    if not hmac.compare_digest(digest, receipt.payload_hash):
        return result("the payload does not match its hash")
    if receipt.id != "rcpt_" + digest[:_ID_HASH_LENGTH]:
        return result("the ID does not match the payload")
    key = ring.keys.get(receipt.key_id)
    if key is None:
        return result(f"unknown key {receipt.key_id!r}")
    if not key.valid_at(issued_at):
        return result(f"key {key.key_id!r} was not valid at {issued_at.isoformat()}")
    signature = base64.urlsafe_b64decode(receipt.signature + "==")
    if not ed25519.verify(key.key, canonical, signature):
        return result("the signature does not match")
    return result()


def _shape_problem(receipt: Receipt) -> str:
    if not _RECEIPT_ID.fullmatch(receipt.id):
        return "malformed receipt ID"
    if not _PAYLOAD_HASH.fullmatch(receipt.payload_hash):
        return "malformed payload hash"
    if not _SIGNATURE.fullmatch(receipt.signature):
        return "malformed signature"
    missing = [field for field in _PAYLOAD_FIELDS if field not in receipt.payload]
    if missing:
        return f"the payload has no {', '.join(missing)}"
    return ""


def _public_key(item: Mapping[str, Any]) -> PublicKey:
    valid_to = item.get("valid_to")
    return PublicKey(
        key_id=str(item["key_id"]),
        key=_spki_key(str(item["public_key"])),
        valid_from=_aware(item["valid_from"]),
        valid_to=None if valid_to is None else _aware(valid_to),
    )


def _spki_key(pem: str) -> bytes:
    lines = [line.strip() for line in pem.strip().splitlines()]
    if (
        not lines
        or lines[0] != "-----BEGIN PUBLIC KEY-----"
        or lines[-1] != "-----END PUBLIC KEY-----"
    ):
        raise KeyRingError("A public key must be a PEM PUBLIC KEY block")
    try:
        der = base64.b64decode("".join(lines[1:-1]), validate=True)
    except binascii.Error as error:
        raise KeyRingError("A public key's PEM body is not base64") from error
    if len(der) != len(_SPKI_PREFIX) + 32 or not der.startswith(_SPKI_PREFIX):
        raise KeyRingError("A public key must be an Ed25519 SubjectPublicKeyInfo")
    return der[len(_SPKI_PREFIX) :]


def _aware(value: object) -> datetime:
    moment = datetime.fromisoformat(str(value))
    if moment.tzinfo is None:
        raise KeyRingError(f"Timestamp without a time zone: {value!r}")
    return moment


def find_receipt(document: object) -> Mapping[str, Any]:
    """The receipt in a JSON document: the receipt itself, or an answer that carries one."""
    if isinstance(document, Mapping):
        inner = document.get("receipt")
        if isinstance(inner, Mapping):
            return inner
        if "signature" in document:
            return document
    raise ValueError("No receipt found: pass a receipt, or an API answer with a receipt")


__all__ = [
    "KeyRing",
    "KeyRingError",
    "PublicKey",
    "Verification",
    "find_receipt",
    "verify_receipt",
]
