"""Ed25519 signature verification (RFC 8032, section 5.1.7) in pure Python.

Only verification, so receipts can be checked offline with no dependency. Everything it
handles is public (keys, messages, signatures), so it needs no constant-time arithmetic.
"""

import hashlib

_P = 2**255 - 19
_L = 2**252 + 27742317777372353535851937790883648493
_D = -121665 * pow(121666, _P - 2, _P) % _P
_SQRT_M1 = pow(2, (_P - 1) // 4, _P)
_KEY_LENGTH = 32
_SIGNATURE_LENGTH = 64

# Points in extended coordinates (X, Y, Z, T), with x = X/Z, y = Y/Z and x*y = T/Z.
type _Point = tuple[int, int, int, int]

_IDENTITY: _Point = (0, 1, 1, 0)
_BASE_X = 15112221349535400772501151409588531511454012693041857206046113283949847762202
_BASE_Y = 46316835694926478169428394003475163141307993866256225615783033603165251855960
_BASE: _Point = (_BASE_X, _BASE_Y, 1, _BASE_X * _BASE_Y % _P)


def verify(public_key: bytes, message: bytes, signature: bytes) -> bool:
    """Whether ``signature`` is ``public_key``'s Ed25519 signature of ``message``."""
    if len(public_key) != _KEY_LENGTH or len(signature) != _SIGNATURE_LENGTH:
        return False
    a = _decode(public_key)
    r = _decode(signature[:32])
    s = int.from_bytes(signature[32:], "little")
    if a is None or r is None or s >= _L:
        return False
    h = int.from_bytes(hashlib.sha512(signature[:32] + public_key + message).digest(), "little")
    return _equal(_multiply(s, _BASE), _add(r, _multiply(h % _L, a)))


def _add(p: _Point, q: _Point) -> _Point:
    a = (p[1] - p[0]) * (q[1] - q[0]) % _P
    b = (p[1] + p[0]) * (q[1] + q[0]) % _P
    c = 2 * p[3] * q[3] * _D % _P
    d = 2 * p[2] * q[2] % _P
    e, f, g, h = b - a, d - c, d + c, b + a
    return (e * f % _P, g * h % _P, f * g % _P, e * h % _P)


def _multiply(scalar: int, point: _Point) -> _Point:
    result = _IDENTITY
    while scalar:
        if scalar & 1:
            result = _add(result, point)
        point = _add(point, point)
        scalar >>= 1
    return result


def _equal(p: _Point, q: _Point) -> bool:
    return (p[0] * q[2] - q[0] * p[2]) % _P == 0 and (p[1] * q[2] - q[1] * p[2]) % _P == 0


def _recover_x(y: int, sign: int) -> int | None:
    if y >= _P:
        return None
    x2 = (y * y - 1) * pow(_D * y * y + 1, _P - 2, _P) % _P
    if x2 == 0:
        return None if sign else 0
    x = pow(x2, (_P + 3) // 8, _P)
    if (x * x - x2) % _P:
        x = x * _SQRT_M1 % _P
    if (x * x - x2) % _P:
        return None
    if x & 1 != sign:
        x = _P - x
    return x


def _decode(encoded: bytes) -> _Point | None:
    y = int.from_bytes(encoded, "little")
    sign = y >> 255
    y &= (1 << 255) - 1
    x = _recover_x(y, sign)
    if x is None:
        return None
    return (x, y, 1, x * y % _P)
