import json
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


def _readme() -> str:
    return (LIB_PYTHON / "README.md").read_text("utf-8")


def test_the_readme_leads_with_a_three_line_example_that_answers_a_chaps_date() -> None:
    # The first thing on the PyPI page is code that runs offline and prints its own answer.
    block = _readme().split("```python\n", 1)[1].split("```", 1)[0]
    lines = [line for line in block.splitlines() if line.strip()]
    assert len(lines) == 3
    assert "GB-CHAPS" in lines[2]
    expected = lines[2].rsplit("# ", 1)[1]
    printed: list[str] = []
    exec(  # noqa: S102 - the README's own example
        compile(block, "README.md", "exec"), {"print": lambda value: printed.append(str(value))}
    )
    assert printed == [expected]
    # Then the MCP configuration, before anything else.
    assert _readme().index('"zeitwide-mcp"') < _readme().index("## Offline")


def test_the_readme_links_every_bundled_calendar_to_its_page() -> None:
    releases = sorted((LIB_PYTHON / "src" / "zeitwide" / "data" / "releases").glob("*.json"))
    calendars = json.loads(releases[-1].read_text("utf-8"))["calendars"]
    for calendar in calendars:
        page = f"https://zeitwide.com/calendars/{calendar['id']}"
        assert f"]({page})" in _readme(), calendar["id"]
