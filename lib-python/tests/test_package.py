import tomllib
from importlib.metadata import entry_points, requires, version
from pathlib import Path

import zeitwide

LIB_PYTHON = Path(__file__).parents[1]


def test_version_matches_distribution_metadata() -> None:
    assert zeitwide.__version__ == version("zeitwide")


def test_install_pulls_only_httpx_plus_the_mcp_extra() -> None:
    # Offline answers need only the standard library; keep it that way.
    assert sorted("".join(line.split()) for line in requires("zeitwide") or []) == [
        "httpx>=0.28.1,<1",
        "mcp>=2.3,<3;extra=='mcp'",
    ]


def test_the_launcher_is_released_in_step_with_the_library() -> None:
    launcher = tomllib.loads((LIB_PYTHON / "zeitwide-mcp" / "pyproject.toml").read_text("utf-8"))

    assert launcher["project"]["version"] == zeitwide.__version__
    assert launcher["project"]["dependencies"] == [f"zeitwide[mcp]=={zeitwide.__version__}"]
    assert launcher["project"]["scripts"] == {"zeitwide-mcp": "zeitwide.mcp_server:main"}


def test_the_zeitwide_command_is_installed() -> None:
    (script,) = entry_points(group="console_scripts", name="zeitwide")

    assert script.value == "zeitwide.cli:main"


def test_the_public_api_is_what_all_exports() -> None:
    for name in zeitwide.__all__:
        assert hasattr(zeitwide, name)
    methods = {"is_business_day", "next_business_day", "add_business_days", "list_closures"}
    assert methods | {"explain_date"} <= set(dir(zeitwide.ZeitWide))
    assert callable(zeitwide.webhooks.verify)
