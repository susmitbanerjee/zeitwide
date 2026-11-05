"""Commits a bundle to the public data repository's default branch and tags the release.

Built on GitHub's Git Data API so one release is one commit holding exactly the bundle's
files (stale files fall away). Every step is safe to repeat: a release that is already
tagged is left alone, and a commit that landed without its tag only needs the tag.
"""

import base64
import json
import re
from collections.abc import Mapping

import httpx

from zeitwide_pipeline.distribution.bundle import MANIFEST, Bundle, Outcome
from zeitwide_pipeline.github import API, TIMEOUT, GitHubError

HTTP_NOT_FOUND = 404
_REPOSITORY = re.compile(r"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")


class DataRepository:
    def __init__(self, client: httpx.Client, repository: str, token: str) -> None:
        if not _REPOSITORY.match(repository):
            raise ValueError("The open data repository is owner/name")
        self._client = client
        self._repository = repository
        self._headers = {
            "Authorization": f"Bearer {token}",
            "Accept": "application/vnd.github+json",
            "X-GitHub-Api-Version": "2022-11-28",
        }

    def publish(self, bundle: Bundle) -> Outcome:
        if self._get_or_none(f"/git/ref/tags/{bundle.release}") is not None:
            return Outcome.ALREADY_PUBLISHED
        branch = _text(self._get(""), "default_branch")
        head = _sha(self._get(f"/git/ref/heads/{branch}"))
        remote = self._remote_manifest(branch)
        if remote.get("release") == bundle.release:
            # The commit landed in an earlier run, which stopped before tagging it.
            self._tag(bundle.release, head)
            return Outcome.PUBLISHED
        if remote.get("data_digest") == bundle.data_digest:
            return Outcome.UNCHANGED
        tree: list[dict[str, str]] = [
            {"path": file.path, "mode": "100644", "type": "blob", "sha": self._blob(file.content)}
            for file in bundle.files
        ]
        tree_sha = _text(self._send("POST", "/git/trees", {"tree": tree}), "sha")
        body: dict[str, object] = {
            "message": f"Dataset release {bundle.release}",
            "tree": tree_sha,
            "parents": [head],
        }
        commit = self._send("POST", "/git/commits", body)
        commit_sha = _text(commit, "sha")
        self._send("PATCH", f"/git/refs/heads/{branch}", {"sha": commit_sha, "force": False})
        self._tag(bundle.release, commit_sha)
        return Outcome.PUBLISHED

    def _remote_manifest(self, branch: str) -> dict[str, object]:
        response = self._client.get(
            self._url(f"/contents/{MANIFEST}"),
            params={"ref": branch},
            headers={**self._headers, "Accept": "application/vnd.github.raw+json"},
            timeout=TIMEOUT,
        )
        if response.status_code == HTTP_NOT_FOUND:
            return {}
        _check(response)
        try:
            manifest = json.loads(response.content)
        except ValueError:
            return {}
        return manifest if isinstance(manifest, dict) else {}

    def _blob(self, content: bytes) -> str:
        body = {"content": base64.b64encode(content).decode(), "encoding": "base64"}
        return _text(self._send("POST", "/git/blobs", body), "sha")

    def _tag(self, name: str, sha: str) -> None:
        self._send("POST", "/git/refs", {"ref": f"refs/tags/{name}", "sha": sha})

    def _url(self, path: str) -> str:
        return f"{API}/repos/{self._repository}{path}"

    def _get(self, path: str) -> dict[str, object]:
        response = self._client.get(self._url(path), headers=self._headers, timeout=TIMEOUT)
        _check(response)
        return _object(response)

    def _get_or_none(self, path: str) -> dict[str, object] | None:
        response = self._client.get(self._url(path), headers=self._headers, timeout=TIMEOUT)
        if response.status_code == HTTP_NOT_FOUND:
            return None
        _check(response)
        return _object(response)

    def _send(self, method: str, path: str, body: Mapping[str, object]) -> dict[str, object]:
        response = self._client.request(
            method, self._url(path), headers=self._headers, json=body, timeout=TIMEOUT
        )
        _check(response)
        return _object(response)


def _check(response: httpx.Response) -> None:
    if response.is_error:
        # GitHub's message only; never the request, which carries the token.
        try:
            message = response.json().get("message", "") if response.content else ""
        except ValueError:
            message = ""
        raise GitHubError(f"GitHub returned {response.status_code}: {message}")


def _object(response: httpx.Response) -> dict[str, object]:
    result = response.json()
    if not isinstance(result, dict):
        raise GitHubError("Unexpected response from GitHub")
    return result


def _text(document: object, key: str) -> str:
    if not isinstance(document, dict) or not isinstance(document.get(key), str):
        raise GitHubError(f"GitHub's response has no {key!r}")
    return str(document[key])


def _sha(ref: dict[str, object]) -> str:
    return _text(ref.get("object"), "sha")
