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

    @property
    def openapi_url(self) -> str:
        return f"{self.api_url.rstrip('/')}/openapi.yaml"

    @property
    def mcp_url(self) -> str:
        return f"{self.api_url.rstrip('/')}/mcp"
