"""Opens a pull request that adds a release file to the public dataset repository.

Every step is safe to repeat: an existing pull request, branch or file for the release
is left as it is, so the daily job can retry after a failure.
"""

import base64
import re

import httpx

API = "https://api.github.com"
TIMEOUT = httpx.Timeout(30.0, connect=5.0)
HTTP_UNPROCESSABLE = 422
_REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class GitHubError(Exception):
    """GitHub refused a request the release needs."""


class DatasetRepository:
    def __init__(self, client: httpx.Client, repository: str, token: str) -> None:
        if not _REPOSITORY.match(repository):
            raise ValueError("The dataset repository is owner/name")
        self._client = client
        self._repository = repository
        self._headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }

    def propose_release(self, release_id: str, content: bytes) -> str:
        """Returns the pull request's URL, opening it if there is none yet."""
        branch = f"release/{release_id}"
        owner = self._repository.split("/")[0]
        existing = self._get(f"/pulls?state=all&head={owner}:{branch}")
        if isinstance(existing, list) and existing:
            return str(existing[0]["html_url"])
        base = _text(self._get(""), "default_branch")
        head = self._get(f"/git/ref/heads/{base}")
        head_sha = _text(head.get("object") if isinstance(head, dict) else None, "sha")
        self._post("/git/refs", {"ref": f"refs/heads/{branch}", "sha": head_sha}, allow_exists=True)
        self._put(
            f"/contents/releases/{release_id}.json",
            {
                "message": f"Add dataset release {release_id}",
                "content": base64.b64encode(content).decode(),
                "branch": branch,
            },
        )
        pull = self._post(
            "/pulls",
            {
                "title": f"Dataset release {release_id}",
                "head": branch,
                "base": base,
                "body": "Cut by the SettleCal release builder. Published under CC BY-NC 4.0.",
            },
        )
        return str(pull["html_url"])

    def _url(self, path: str) -> str:
        return f"{API}/repos/{self._repository}{path}"

    def _get(self, path: str) -> dict[str, object] | list[dict[str, object]]:
        response = self._client.get(self._url(path), headers=self._headers, timeout=TIMEOUT)
        return self._json(response)

    def _post(
        self, path: str, body: dict[str, object], *, allow_exists: bool = False
    ) -> dict[str, object]:
        response = self._client.post(
            self._url(path), headers=self._headers, json=body, timeout=TIMEOUT
        )
        if allow_exists and response.status_code == HTTP_UNPROCESSABLE:
            return {}
        result = self._json(response)
        if not isinstance(result, dict):
            raise GitHubError(f"Unexpected response from {path}")
        return result

    def _put(self, path: str, body: dict[str, object]) -> None:
        response = self._client.put(
            self._url(path), headers=self._headers, json=body, timeout=TIMEOUT
        )
        # 422 here means the file is already on the branch from an earlier attempt.
        if response.status_code != HTTP_UNPROCESSABLE:
            self._json(response)

    @staticmethod
    def _json(response: httpx.Response) -> dict[str, object] | list[dict[str, object]]:
        if response.is_error:
            # GitHub's message only; never the request, which carries the token.
            message = response.json().get("message", "") if response.content else ""
            raise GitHubError(f"GitHub returned {response.status_code}: {message}")
        result: dict[str, object] | list[dict[str, object]] = response.json()
        return result


def _text(document: object, key: str) -> str:
    if not isinstance(document, dict) or not isinstance(document.get(key), str):
        raise GitHubError(f"GitHub's response has no {key!r}")
    return str(document[key])
