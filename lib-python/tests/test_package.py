from importlib.metadata import version

import settlecal


def test_version_matches_distribution_metadata() -> None:
    assert settlecal.__version__ == version("settlecal")
