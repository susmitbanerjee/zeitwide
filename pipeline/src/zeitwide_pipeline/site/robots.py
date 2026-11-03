"""robots.txt, from the bot list in ``agents.toml``."""

import tomllib
from dataclasses import dataclass
from importlib import resources

from zeitwide_pipeline.site.links import SiteLinks


@dataclass(frozen=True, slots=True)
class AgentGroup:
    comment: str
    agents: tuple[str, ...]


@dataclass(frozen=True, slots=True)
class RobotsConfig:
    disallow: tuple[str, ...]
    groups: tuple[AgentGroup, ...]

    @property
    def named_agents(self) -> int:
        return sum(len(group.agents) for group in self.groups)


def load_robots_config(text: str | None = None) -> RobotsConfig:
    raw = tomllib.loads(
        text
        if text is not None
        else resources.files(__package__).joinpath("agents.toml").read_text(encoding="utf-8")
    )
    return RobotsConfig(
        disallow=tuple(str(path) for path in raw.get("disallow", [])),
        groups=tuple(
            AgentGroup(str(group["comment"]), tuple(str(a) for a in group["agents"]))
            for group in raw.get("groups", [])
        ),
    )


def robots_txt(config: RobotsConfig, links: SiteLinks) -> str:
    """Everyone may crawl everything but the closed paths; named agents get the same rules.

    A crawler follows only the most specific group naming it, so each named group repeats the
    rules instead of inheriting them from ``*``.
    """
    rules = ["Allow: /", *(f"Disallow: {path}" for path in config.disallow)]
    lines = ["User-agent: *", *rules]
    for group in config.groups:
        lines += ["", f"# {group.comment}", *(f"User-agent: {a}" for a in group.agents), *rules]
    lines += [
        "",
        f"Sitemap: {links.page('/sitemap.xml')}",
        f"# llms.txt: {links.page('/llms.txt')}",
    ]
    return "\n".join(lines) + "\n"
