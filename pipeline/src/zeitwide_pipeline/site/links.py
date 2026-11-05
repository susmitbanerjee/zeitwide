"""Where the public site, the API and the MCP server live, for links in the site files."""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class SiteLinks:
    site_url: str
    api_url: str

    def page(self, path: str) -> str:
        return f"{self.site_url.rstrip('/')}{path}"

    def markdown(self, path: str) -> str:
        """The Markdown twin of a page: its path plus ``.md``."""
        return self.page(f"{path}.md")

    def api(self, path: str) -> str:
        return f"{self.api_url.rstrip('/')}{path}"

    @property
    def openapi_url(self) -> str:
        """The website serves the OpenAPI document as JSON; the API serves no copy."""
        return self.page("/openapi.json")

    @property
    def mcp_url(self) -> str:
        return self.api("/mcp")

    @property
    def a2a_url(self) -> str:
        return self.api("/a2a")

    @property
    def server_card_url(self) -> str:
        return self.api("/v1/server-card")
