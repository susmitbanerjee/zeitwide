from importlib.metadata import version

import zeitwide


def test_version_matches_distribution_metadata() -> None:
    assert zeitwide.__version__ == version("zeitwide")
