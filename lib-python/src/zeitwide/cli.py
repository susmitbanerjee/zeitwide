"""The ``zeitwide`` command: ``zeitwide verify <receipt>`` and ``zeitwide mcp``.

``verify`` never touches the network: it checks the receipt against the bundled copy of
``zeitwide-keys.json``, or the ring given with ``--keys``.
"""

import argparse
import json
import sys
from collections.abc import Sequence
from pathlib import Path

from zeitwide import __version__
from zeitwide.receipts import KeyRing, KeyRingError, find_receipt, verify_receipt

EXIT_VALID = 0
EXIT_INVALID = 1
EXIT_USAGE = 2


def main(argv: Sequence[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="zeitwide", description=__doc__.splitlines()[0])
    parser.add_argument("--version", action="version", version=f"zeitwide {__version__}")
    commands = parser.add_subparsers(dest="command", required=True)

    verify = commands.add_parser(
        "verify",
        help="check a receipt's signature offline",
        description="Checks a receipt offline: its hash, ID, Ed25519 signature and key.",
    )
    verify.add_argument(
        "receipt",
        help="a JSON file with a receipt, or an API answer that carries one ('-' for stdin)",
    )
    verify.add_argument(
        "--keys",
        type=Path,
        help="zeitwide-keys.json to trust instead of the copy bundled with this version",
    )

    commands.add_parser(
        "mcp",
        help="run the local MCP server (needs zeitwide[mcp]); see zeitwide mcp --help",
        add_help=False,
    )

    args, rest = parser.parse_known_args(argv)
    if args.command == "mcp":
        return _mcp(rest)
    if rest:
        parser.error(f"unrecognized arguments: {' '.join(rest)}")
    return _verify(args.receipt, args.keys)


def _verify(source: str, keys_path: Path | None) -> int:
    try:
        text = sys.stdin.read() if source == "-" else Path(source).read_text(encoding="utf-8")
        receipt = find_receipt(json.loads(text))
        keys = KeyRing.bundled() if keys_path is None else KeyRing.parse(keys_path.read_bytes())
    except (OSError, ValueError) as error:
        # KeyRingError and JSON errors are ValueErrors too.
        kind = "key ring" if isinstance(error, KeyRingError) else "receipt"
        sys.stderr.write(f"zeitwide verify: cannot read the {kind}: {error}\n")
        return EXIT_USAGE
    result = verify_receipt(receipt, keys)
    if result:
        issued = result.issued_at.isoformat() if result.issued_at else "?"
        sys.stdout.write(f"valid: {result.receipt_id} signed by {result.key_id}, issued {issued}\n")
        return EXIT_VALID
    sys.stdout.write(f"INVALID: {result.reason}\n")
    return EXIT_INVALID


def _mcp(argv: Sequence[str]) -> int:
    try:
        from zeitwide import mcp_server  # noqa: PLC0415 - only with the mcp extra
    except ImportError:
        sys.stderr.write('The MCP server needs the extra: pip install "zeitwide[mcp]"\n')
        return EXIT_USAGE
    return mcp_server.main(argv)


if __name__ == "__main__":
    sys.exit(main())
