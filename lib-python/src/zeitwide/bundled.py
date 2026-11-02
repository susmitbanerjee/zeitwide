"""The data that ships inside the package: the latest dataset release and the receipt keys.

The release is under CC BY-NC 4.0 (``LICENSE-DATA``); the code that reads it is MIT.
Nothing here touches the network.
"""

from functools import cache
from importlib.resources import files
from importlib.resources.abc import Traversable

from zeitwide.release import Release, ReleaseError, parse_release, release_version

_DATA = "zeitwide.data"


@cache
def latest_release() -> Release:
    """The newest dataset release bundled with this version of the library."""
    return parse_release(latest_release_file().read_bytes())


def latest_release_file() -> Traversable:
    releases = [
        item for item in files(_DATA).joinpath("releases").iterdir() if item.name.endswith(".json")
    ]
    if not releases:
        raise ReleaseError("This build of zeitwide has no bundled release")
    return max(releases, key=lambda item: release_version(item.name.removesuffix(".json")))


def receipt_keys() -> bytes:
    """``zeitwide-keys.json`` as published at ``/.well-known/`` when this version was built."""
    return files(_DATA).joinpath("zeitwide-keys.json").read_bytes()
