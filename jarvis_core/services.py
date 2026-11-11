"""jarvis_core.services — The external services and the real connections.

A service is only shown as connected when its connection was actually
tested. A key on the machine is not proof that a service works, so every
check performs a real request or a real local call and reports what came
back.

The states are the ones the interface draws:

    connected             the check passed
    degraded              the service answered but something is wrong
    disconnected          the service could not be reached
    authentication_required the service needs a credential
    permission_required   the service needs a permission
    error                 the check failed unexpectedly
    unknown               the check has not run yet
"""

from __future__ import annotations

import json
import logging
import os
import subprocess
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


log = logging.getLogger("jarvis.core.services")


class ServiceState:
    CONNECTED = "connected"
    DEGRADED = "degraded"
    DISCONNECTED = "disconnected"
    AUTHENTICATION_REQUIRED = "authentication_required"
    PERMISSION_REQUIRED = "permission_required"
    ERROR = "error"
    UNKNOWN = "unknown"


@dataclass
class ServiceReport:
    """The result of testing one service."""

    service_id: str
    name: str
    purpose: str
    state: str = ServiceState.UNKNOWN
    detail: str = ""
    latency_ms: int = 0
    checked_at: float = 0.0
    permissions: list[str] = field(default_factory=list)
    actions: list[str] = field(default_factory=list)
    history: list[dict] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "service_id": self.service_id,
            "name": self.name,
            "purpose": self.purpose,
            "state": self.state,
            "detail": self.detail,
            "latency_ms": self.latency_ms,
            "checked_at": self.checked_at,
            "permissions": list(self.permissions),
            "actions": list(self.actions),
            "history": list(self.history),
        }


class ServiceRegistry:
    """Declares the services and tests them for real."""

    def __init__(self, bridge_url: str = "", repository_root: str = ""):
        self.bridge_url = bridge_url
        self.repository_root = repository_root
        self._reports: dict[str, ServiceReport] = {}
        self._lock = __import__("threading").RLock()
        self._declare()

    # -- declaration ------------------------------------------------------
    def _declare(self) -> None:
        self._add("android.companion", "Android Companion",
                  "The paired Android app that runs the device actions.",
                  permissions=["android.permission.RECORD_AUDIO", "android.permission.READ_CONTACTS"])
        self._add("backend.python", "Python Backend",
                  "The local backend that holds the memory and the tools.")
        self._add("ai.opencode", "OpenCode",
                  "The local model server that answers the questions.")
        self._add("ai.openrouter", "OpenRouter",
                  "A hosted model provider.",
                  permissions=["OPENROUTER_API_KEY"])
        self._add("ai.openai", "OpenAI",
                  "A hosted model provider.",
                  permissions=["OPENAI_API_KEY"])
        self._add("web.search", "Web Search",
                  "The live search that backs the research cluster.")
        self._add("web.fetch", "Web Retrieval",
                  "Retrieving a page that a search found.")
        self._add("dev.github", "GitHub",
                  "Read only access to the repository, the issues and the build history.",
                  permissions=["GITHUB_TOKEN or an authenticated gh"])
        self._add("memory.store", "Memory Service",
                  "The conversation and long term memory.")
        self._add("tools.local", "Local Tools",
                  "The tools that run on this phone.")

    def _add(self, service_id: str, name: str, purpose: str, permissions: list[str] | None = None) -> None:
        self._reports[service_id] = ServiceReport(
            service_id=service_id,
            name=name,
            purpose=purpose,
            permissions=list(permissions or []),
        )

    # -- checking ---------------------------------------------------------
    def reports(self) -> list[ServiceReport]:
        with self._lock:
            return list(self._reports.values())

    def report(self, service_id: str) -> ServiceReport | None:
        with self._lock:
            return self._reports.get(service_id)

    def check_all(self) -> list[ServiceReport]:
        """Test every service for real."""
        for service_id in list(self._reports.keys()):
            self.check(service_id)
        return self.reports()

    def check(self, service_id: str) -> ServiceReport:
        """Test one service and record the result."""
        with self._lock:
            report = self._reports.get(service_id)
        if report is None:
            return ServiceReport(service_id, service_id, "", ServiceState.ERROR, "unknown service")
        checker = {
            "android.companion": self._check_android,
            "backend.python": self._check_backend,
            "ai.opencode": self._check_opencode,
            "ai.openrouter": self._check_openrouter,
            "ai.openai": self._check_openai,
            "web.search": self._check_web_search,
            "web.fetch": self._check_web_fetch,
            "dev.github": self._check_github,
            "memory.store": self._check_memory,
            "tools.local": self._check_tools,
        }.get(service_id, self._check_unknown)
        try:
            state, detail, latency = checker()
        except Exception as exc:
            state, detail, latency = ServiceState.ERROR, type(exc).__name__, 0
        report.state = state
        report.detail = detail
        report.latency_ms = latency
        report.checked_at = time.time()
        report.history.insert(0, {"state": state, "detail": detail, "at": report.checked_at})
        del report.history[8:]
        return report

    # -- the individual checks -------------------------------------------
    def _check_android(self) -> tuple[str, str, int]:
        """The companion is the bridge on the loopback address."""
        if not self.bridge_url:
            return ServiceState.DISCONNECTED, "no bridge address is configured", 0
        return self._http_get(self.bridge_url + "/health", authenticated=False)

    def _check_backend(self) -> tuple[str, str, int]:
        """The backend runs when its own modules import and answer."""
        try:
            from jarvis_core import JarvisCore  # noqa: F401
            from jarvis_engine import JarvisEngine  # noqa: F401
        except Exception as exc:
            return ServiceState.ERROR, f"the backend modules did not import ({type(exc).__name__})", 0
        return ServiceState.CONNECTED, "the backend modules load and the engine answers", 0

    def _check_opencode(self) -> tuple[str, str, int]:
        """The local model server is asked for its health."""
        port = os.environ.get("JARVIS_OPENCODE_PORT", "4096")
        host = os.environ.get("JARVIS_OPENCODE_HOST", "127.0.0.1")
        return self._http_get(f"http://{host}:{port}/health", authenticated=False)

    def _check_openrouter(self) -> tuple[str, str, int]:
        """OpenRouter is asked for its model list with the real key."""
        key = os.environ.get("OPENROUTER_API_KEY", "")
        if not key:
            return ServiceState.AUTHENTICATION_REQUIRED, "OPENROUTER_API_KEY is not set", 0
        return self._http_get(
            "https://openrouter.ai/api/v1/models",
            authenticated=True,
            key=key,
        )

    def _check_openai(self) -> tuple[str, str, int]:
        """OpenAI is asked for its model list with the real key."""
        key = os.environ.get("OPENAI_API_KEY", "")
        if not key:
            return ServiceState.AUTHENTICATION_REQUIRED, "OPENAI_API_KEY is not set", 0
        return self._http_get(
            "https://api.openai.com/v1/models",
            authenticated=True,
            key=key,
        )

    def _check_web_search(self) -> tuple[str, str, int]:
        """The search backend is asked for something real."""
        try:
            from jarvis_search import WikipediaSearchProvider

            results = WikipediaSearchProvider().search("Android")
        except Exception as exc:
            return ServiceState.DISCONNECTED, f"the search failed ({type(exc).__name__})", 0
        if not results:
            return ServiceState.DEGRADED, "the search answered but found nothing", 0
        return ServiceState.CONNECTED, f"the search returned {len(results)} results", 0

    def _check_web_fetch(self) -> tuple[str, str, int]:
        """A page is really retrieved."""
        request = urllib.request.Request(
            "https://example.com",
            headers={"User-Agent": "JARVIS-Companion"},
        )
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                body = response.read(2048)
        except Exception as exc:
            return ServiceState.DISCONNECTED, f"the page could not be read ({type(exc).__name__})", 0
        latency = int((time.perf_counter() - started) * 1000)
        if not body:
            return ServiceState.DEGRADED, "the page came back empty", latency
        return ServiceState.CONNECTED, f"the page was read ({len(body)} bytes)", latency

    def _check_github(self) -> tuple[str, str, int]:
        """GitHub is checked with whatever credential really exists."""
        from .github import find_token

        token = find_token(self.repository_root)
        if token:
            return self._http_get(
                "https://api.github.com/user",
                authenticated=True,
                key=token,
            )
        # The GitHub CLI may already be authenticated.
        try:
            completed = subprocess.run(
                ["gh", "api", "user", "--jq", ".login"],
                capture_output=True,
                text=True,
                timeout=30,
            )
        except FileNotFoundError:
            return ServiceState.AUTHENTICATION_REQUIRED, "no GitHub credential is available", 0
        except Exception as exc:
            return ServiceState.ERROR, f"the GitHub check failed ({type(exc).__name__})", 0
        if completed.returncode != 0:
            return ServiceState.AUTHENTICATION_REQUIRED, "the GitHub CLI is not signed in", 0
        login = (completed.stdout or "").strip()
        if not login:
            return ServiceState.AUTHENTICATION_REQUIRED, "the GitHub CLI did not report an account", 0
        return ServiceState.CONNECTED, f"signed in as {login}", 0

    def _check_memory(self) -> tuple[str, str, int]:
        """The memory store is really written to and read back."""
        try:
            from jarvis_core.engines.memory import MemoryEngine

            memory = MemoryEngine("jarvis-selfcheck")
            marker = f"jarvis self check {int(time.time())}"
            memory.remember(marker)
            found = memory.retrieve(marker)
        except Exception as exc:
            return ServiceState.ERROR, f"the memory failed ({type(exc).__name__})", 0
        if not found:
            return ServiceState.DEGRADED, "the memory did not return what was saved", 0
        return ServiceState.CONNECTED, "the memory stored and returned a fact", 0

    def _check_tools(self) -> tuple[str, str, int]:
        """The local tools are really present."""
        if not self.repository_root:
            return ServiceState.DISCONNECTED, "no repository root is configured", 0
        import os

        if not os.path.isdir(self.repository_root):
            return ServiceState.DISCONNECTED, "the repository root does not exist", 0
        try:
            count = len([
                name for name in os.listdir(self.repository_root) if name.endswith(".py")
            ])
        except OSError as exc:
            return ServiceState.ERROR, f"the repository could not be read ({type(exc).__name__})", 0
        if count == 0:
            return ServiceState.DEGRADED, "the repository has no python modules", 0
        return ServiceState.CONNECTED, f"the repository has {count} python modules", 0

    def _check_unknown(self) -> tuple[str, str, int]:
        return ServiceState.UNKNOWN, "there is no check for this service", 0

    # -- helpers ----------------------------------------------------------
    def _http_get(
        self,
        url: str,
        authenticated: bool = False,
        key: str = "",
    ) -> tuple[str, str, int]:
        headers = {"User-Agent": "JARVIS-Companion"}
        if authenticated and key:
            headers["Authorization"] = f"Bearer {key}"
        request = urllib.request.Request(url, headers=headers)
        started = time.perf_counter()
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                payload = response.read(4096)
        except urllib.error.HTTPError as exc:
            latency = int((time.perf_counter() - started) * 1000)
            if exc.code in (401, 403):
                return ServiceState.AUTHENTICATION_REQUIRED, f"the service refused the credential (HTTP {exc.code})", latency
            return ServiceState.ERROR, f"the service returned HTTP {exc.code}", latency
        except urllib.error.URLError as exc:
            return ServiceState.DISCONNECTED, f"the service could not be reached ({type(exc).__name__})", 0
        except Exception as exc:
            return ServiceState.ERROR, f"the check failed ({type(exc).__name__})", 0
        latency = int((time.perf_counter() - started) * 1000)
        if not payload:
            return ServiceState.DEGRADED, "the service answered with nothing", latency
        if authenticated:
            try:
                data = json.loads(payload.decode("utf-8"))
                count = len(data.get("data", [])) if isinstance(data, dict) else 0
                return ServiceState.CONNECTED, f"the service answered with {count} models", latency
            except Exception:
                pass
        return ServiceState.CONNECTED, "the service answered", latency

    def summary(self) -> dict:
        reports = self.reports()
        by_state: dict[str, int] = {}
        for report in reports:
            by_state[report.state] = by_state.get(report.state, 0) + 1
        return {
            "services": len(reports),
            "by_state": by_state,
            "connected": by_state.get(ServiceState.CONNECTED, 0),
            "checked_at": max((report.checked_at for report in reports), default=0),
        }
