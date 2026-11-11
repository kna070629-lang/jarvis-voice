"""jarvis_core.github — Read only GitHub access.

The credential is never printed and never leaves this module. It is taken
from the environment or from an existing GitHub CLI login, and it is only
used for read operations. Nothing here writes to a repository, opens a pull
request or pushes anything.
"""

from __future__ import annotations

import json
import logging
import os
import re
import urllib.error
import urllib.request
from pathlib import Path


log = logging.getLogger("jarvis.core.github")

API = "https://api.github.com"


class GitHubError(RuntimeError):
    """A GitHub call failed. The message never contains the token."""


def find_token(repository_root: str = "") -> str:
    """Find a GitHub credential that really exists.

    The environment is checked first, then a GitHub CLI login. The token is
    returned so a call can be made, and it is never logged.
    """
    for name in ("GITHUB_TOKEN", "GH_TOKEN"):
        value = os.environ.get(name, "").strip()
        if value:
            return value

    candidates: list[Path] = []
    if repository_root:
        root = Path(repository_root).resolve()
        candidates.append(root.parent / ".config" / "gh" / "hosts.yml")
        candidates.append(root / ".config" / "gh" / "hosts.yml")
    candidates.append(Path.home() / ".config" / "gh" / "hosts.yml")

    for path in candidates:
        try:
            if not path.is_file():
                continue
            text = path.read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue
        match = re.search(r"oauth_token:\s*([A-Za-z0-9_\-]+)", text)
        if match:
            return match.group(1)
    return ""


class GitHubClient:
    """Reads from the GitHub API."""

    def __init__(self, token: str = "", repository: str = "", timeout: float = 25.0):
        self.token = token
        self.repository = repository
        self.timeout = timeout
        self.calls = 0
        self.failures = 0
        self.last_error = ""

    def installed(self) -> bool:
        return bool(self.token and self.repository)

    def _get(self, path: str) -> object:
        if not self.token:
            raise GitHubError("GitHub is not authenticated, so nothing was requested.")
        if not self.repository:
            raise GitHubError("No repository is configured.")
        url = f"{API}/repos/{self.repository}{path}"
        request = urllib.request.Request(url, headers={
            "Authorization": f"Bearer {self.token}",
            "Accept": "application/vnd.github+json",
            "User-Agent": "JARVIS-Companion",
        })
        self.calls += 1
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = response.read()
        except urllib.error.HTTPError as exc:
            self.failures += 1
            self.last_error = f"GitHub returned HTTP {exc.code}"
            raise GitHubError(self.last_error) from None
        except Exception as exc:
            self.failures += 1
            self.last_error = f"GitHub could not be reached ({type(exc).__name__})"
            raise GitHubError(self.last_error) from None
        try:
            return json.loads(payload.decode("utf-8"))
        except Exception:
            self.failures += 1
            raise GitHubError("GitHub returned something that could not be read") from None

    # -- the read operations ----------------------------------------------
    def repository_info(self) -> dict:
        data = self._get("")
        return {
            "name": data.get("full_name", self.repository),
            "description": data.get("description") or "",
            "default_branch": data.get("default_branch", ""),
            "stars": data.get("stargazers_count", 0),
            "forks": data.get("forks_count", 0),
            "open_issues": data.get("open_issues_count", 0),
            "private": bool(data.get("private")),
            "pushed_at": data.get("pushed_at", ""),
        }

    def branches(self) -> list[dict]:
        data = self._get("/branches")
        if not isinstance(data, list):
            return []
        return [
            {"name": item.get("name", ""), "sha": (item.get("commit") or {}).get("sha", "")[:12]}
            for item in data
        ]

    def commits(self, limit: int = 10) -> list[dict]:
        data = self._get(f"/commits?per_page={max(1, min(limit, 50))}")
        if not isinstance(data, list):
            return []
        commits = []
        for item in data:
            commit = item.get("commit") or {}
            author = commit.get("author") or {}
            commits.append({
                "sha": (item.get("sha") or "")[:12],
                "message": (commit.get("message") or "").splitlines()[0][:160],
                "author": author.get("name", ""),
                "date": author.get("date", ""),
            })
        return commits

    def issues(self, limit: int = 10, state: str = "all") -> list[dict]:
        data = self._get(f"/issues?state={state}&per_page={max(1, min(limit, 50))}")
        if not isinstance(data, list):
            return []
        return [
            {
                "number": item.get("number", 0),
                "title": item.get("title", "")[:160],
                "state": item.get("state", ""),
                "pull_request": "pull_request" in item,
            }
            for item in data
        ]

    def pull_requests(self, limit: int = 10, state: str = "all") -> list[dict]:
        data = self._get(f"/pulls?state={state}&per_page={max(1, min(limit, 50))}")
        if not isinstance(data, list):
            return []
        return [
            {
                "number": item.get("number", 0),
                "title": item.get("title", "")[:160],
                "state": item.get("state", ""),
                "draft": bool(item.get("draft")),
            }
            for item in data
        ]

    def workflow_runs(self, limit: int = 10) -> list[dict]:
        data = self._get(f"/actions/runs?per_page={max(1, min(limit, 50))}")
        if not isinstance(data, dict):
            return []
        runs = data.get("workflow_runs") or []
        return [
            {
                "name": item.get("name", ""),
                "status": item.get("status", ""),
                "conclusion": item.get("conclusion") or "",
                "branch": (item.get("head_branch") or "")[:80],
                "created_at": item.get("created_at", ""),
            }
            for item in runs
        ]

    def file_content(self, path: str, ref: str = "") -> str:
        suffix = f"?ref={ref}" if ref else ""
        data = self._get(f"/contents/{path.lstrip('/')}{suffix}")
        if not isinstance(data, dict):
            return ""
        import base64

        content = data.get("content") or ""
        if data.get("encoding") == "base64" and content:
            try:
                return base64.b64decode(content).decode("utf-8", errors="replace")
            except Exception:
                return ""
        return str(content)

    def stats(self) -> dict:
        return {
            "calls": self.calls,
            "failures": self.failures,
            "last_error": self.last_error,
            "repository": self.repository,
        }
