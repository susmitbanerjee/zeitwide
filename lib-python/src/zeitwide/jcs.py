"""RFC 8785 JSON Canonicalization Scheme: the one byte form of a receipt payload that is signed.

The API canonicalizes with the RFC's reference implementation (``org.erdtman.jcs``); this
gives the same bytes for any I-JSON value.
"""

import json
import math
from collections.abc import Mapping, Sequence
from decimal import Decimal

# Beyond this, an integer is no longer exact as an IEEE 754 double, which is all JSON has.
_MAX_EXACT_INTEGER = 2**53
# ECMAScript writes numbers from 1e21 up, and below 1e-6, with an exponent.
_MAX_PLAIN_EXPONENT = 21
_MIN_PLAIN_EXPONENT = -6


def canonicalize(value: object) -> bytes:
    """The RFC 8785 canonical UTF-8 bytes of a JSON value."""
    return _serialize(value).encode("utf-8")


def _serialize(value: object) -> str:  # noqa: PLR0911 - one return per JSON type
    if value is None:
        return "null"
    if value is True:
        return "true"
    if value is False:
        return "false"
    if isinstance(value, str):
        # Only '"', '\\' and control characters are escaped, exactly as RFC 8785 asks.
        return json.dumps(value, ensure_ascii=False)
    if isinstance(value, int):
        return str(value) if abs(value) <= _MAX_EXACT_INTEGER else _number(float(value))
    if isinstance(value, float):
        return _number(value)
    if isinstance(value, Mapping):
        keys = sorted((_key(key) for key in value), key=lambda key: key.encode("utf-16-be"))
        return "{" + ",".join(f"{_serialize(key)}:{_serialize(value[key])}" for key in keys) + "}"
    if isinstance(value, Sequence):
        return "[" + ",".join(_serialize(item) for item in value) + "]"
    raise TypeError(f"Not a JSON value: {type(value).__name__}")


def _key(key: object) -> str:
    if not isinstance(key, str):
        raise TypeError("JSON object keys must be strings")
    return key


def _number(value: float) -> str:
    """ECMAScript's Number.prototype.toString, which RFC 8785 uses for every number."""
    if not math.isfinite(value):
        raise ValueError("JSON has no NaN or Infinity")
    if value == 0:
        return "0"
    sign = "-" if value < 0 else ""
    # repr is the shortest form that round-trips, as ECMAScript's is; only the layout differs.
    _, digit_tuple, exponent = Decimal(repr(abs(value))).as_tuple()
    digits = "".join(str(digit) for digit in digit_tuple).rstrip("0")
    exponent = int(exponent) + len(digit_tuple) - len(digits)
    count = len(digits)
    point = count + exponent  # where the decimal point falls, counted from the first digit
    if count <= point <= _MAX_PLAIN_EXPONENT:
        return sign + digits + "0" * (point - count)
    if 0 < point <= _MAX_PLAIN_EXPONENT:
        return sign + digits[:point] + "." + digits[point:]
    if _MIN_PLAIN_EXPONENT < point <= 0:
        return sign + "0." + "0" * -point + digits
    mantissa = digits[0] + ("." + digits[1:] if count > 1 else "")
    power = point - 1
    return f"{sign}{mantissa}e{'+' if power >= 0 else '-'}{abs(power)}"
