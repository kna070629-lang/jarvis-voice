"""jarvis_server_provider.py — Fast AI provider backed by a persistent OpenCode server.

Running `opencode run` for every question spawns a process, prepares a runtime
and starts a fresh model session, which is why replies used to take 15 to 25
seconds. This provider keeps one `opencode serve` process and one session
alive, so the model stays warm and a reply arrives in a couple of seconds.

It also supports streaming, which lets the companion start speaking the first
sentence while the rest of the answer is still being written.
"""

from __future__ import annotations

import logging
import threading
import time

from ai_provider import AIReply, ProviderError, ProviderUnavailable


log = logging.getLogger("jarvis.server")

DEFAULT_MODEL = "nemotron-3.5-lightning-free"
DEFAULT_AGENT = "jarvis_chat"
DEFAULT_SESSION = "JARVIS"
# Low effort keeps the model from thinking for many seconds before it replies.
DEFAULT_VARIANT = "low"

# The OpenCode API takes the model without a provider prefix because the
# provider is sent separately, while the CLI needs the prefixed form.
SERVER_AGENT_CONFIG = {
    "$schema": "https://opencode.ai/config.json",
    "autoupdate": False,
    "share": "disabled",
    "agent": {
        "jarvis_chat": {
            "mode": "primary",
            "description": "Text-only JARVIS assistant with every mutating tool disabled.",
            # Denying "read" makes OpenCode stall for minutes and answer nothing.
            "tools": {
                "bash": False,
                "write": False,
                "edit": False,
                "task": False,
                "todowrite": False,
                "webfetch": False,
            },
        }
    },
}


def default_work_dir():
    """Return a small working directory for the OpenCode server.

    The server builds a project context from its working directory. Running it
    in the home directory, which holds the whole OpenCode runtime, adds a large
    amount of context to every request.
    """
    from pathlib import Path
    import tempfile

    path = Path(tempfile.gettempdir()) / "jarvis-opencode-work"
    path.mkdir(parents=True, exist_ok=True)
    return path


class OpenCodeServerProvider:
    """Answers through a warm, persistent OpenCode server and session."""

    name = "opencode-server"

    def __init__(
        self,
        *,
        model: str = "",
        agent: str = DEFAULT_AGENT,
        timeout: float = 120.0,
        session_title: str = DEFAULT_SESSION,
        reuse_session: bool = False,
        work_dir=None,
        variant: str = DEFAULT_VARIANT,
    ):
        self.model = (model or DEFAULT_MODEL).strip()
        if self.model.startswith("opencode/"):
            self.model = self.model.split("/", 1)[1]
        self.agent = agent or DEFAULT_AGENT
        self.timeout = max(15.0, min(float(timeout), 600.0))
        self.session_title = session_title or DEFAULT_SESSION
        # A session that is reused grows without bound and every later turn
        # pays for the whole history. The engine keeps the real conversation
        # memory, so a fresh session per turn is both faster and correct.
        self.reuse_session = reuse_session
        self.work_dir = work_dir
        self.variant = (variant or "").strip()
        self._client = None
        self._lock = threading.Lock()
        self.last_error = ""
        self.turns = 0

    # -- internals -------------------------------------------------------
    def _ensure_client(self):
        if self._client is not None:
            return self._client
        with self._lock:
            if self._client is not None:
                return self._client
            try:
                import opencode_client
            except Exception as exc:  # pragma: no cover - import guard
                raise ProviderUnavailable(self.name, "the OpenCode client is unavailable") from exc
            config = opencode_client.OpenCodeConfig.from_env()
            config.model = self.model
            config.agent = self.agent
            config.session_title = self.session_title
            config.variant = self.variant
            config.stream_timeout = self.timeout
            config.request_timeout = min(self.timeout, 90.0)
            config.system_prompt = ""
            config.config_content = dict(SERVER_AGENT_CONFIG)
            if self.work_dir:
                config.work_dir = self.work_dir
            else:
                config.work_dir = default_work_dir()
            self._client = opencode_client.OpenCodeClient(config)
            return self._client

    def _session_id(self, client) -> str:
        """Return the session for this turn, rotating it when it gets heavy."""
        if self.reuse_session:
            return client.get_or_create_session()
        session = client._request_json("POST", "/session", {"title": self.session_title}, timeout=30)
        if isinstance(session, dict) and session.get("id"):
            return str(session["id"])
        return client.get_or_create_session()

    def _prepare(self, context: str | None):
        client = self._ensure_client()
        # The system prompt is set per request. The engine serialises turns, and
        # the lock keeps a streaming request from changing it mid-flight.
        client.config.system_prompt = context or ""
        return client

    # -- provider interface ----------------------------------------------
    def installed(self) -> bool:
        try:
            import shutil

            return shutil.which("opencode") is not None
        except Exception:
            return False

    def ask(self, text: str, context: str | None = None) -> AIReply:
        if not isinstance(text, str) or not text.strip():
            raise ProviderError(self.name, "the request was empty")
        client = self._prepare(context)
        try:
            session_id = self._session_id(client)
            reply = client.ask(text, session_id)
            self.turns += 1
        except Exception as exc:
            self.last_error = type(exc).__name__
            # A dead server must not poison later turns.
            self.reset()
            raise ProviderUnavailable(self.name, "the OpenCode server did not answer") from exc
        if not isinstance(reply, str) or not reply.strip():
            raise ProviderError(self.name, "the server returned no text")
        return AIReply(reply.strip(), self.name)

    def ask_stream(self, text: str, context: str | None = None, on_delta=None) -> str:
        """Stream the answer, calling [on_delta] with each new piece of text."""
        if not isinstance(text, str) or not text.strip():
            raise ProviderError(self.name, "the request was empty")
        client = self._prepare(context)
        try:
            session_id = self._session_id(client)
            reply = client.ask_stream(text, session_id, on_delta=on_delta)
            self.turns += 1
        except Exception as exc:
            self.last_error = type(exc).__name__
            self.reset()
            raise ProviderUnavailable(self.name, "the OpenCode server did not answer") from exc
        if not isinstance(reply, str) or not reply.strip():
            raise ProviderError(self.name, "the server returned no text")
        return reply.strip()

    # -- lifecycle --------------------------------------------------------
    def reset(self) -> None:
        """Drop the session so the next turn starts from a clean context."""
        client = self._client
        if client is None:
            return
        try:
            client.reset_session()
        except Exception:
            log.debug("Could not reset the OpenCode session", exc_info=True)

    def warm(self) -> bool:
        """Start the server and prime the session so the first reply is fast."""
        try:
            client = self._ensure_client()
            client.ensure_server()
        except Exception:
            log.warning("Could not warm the OpenCode server", exc_info=True)
            return False
        try:
            # A throwaway prompt pays the model warm-up cost once.
            client.ask("Reply with the single word ready.")
            return True
        except Exception:
            log.info("The OpenCode server is up but the warm-up prompt failed", exc_info=True)
            return True

    def health(self) -> dict[str, object]:
        client = self._client
        if client is None:
            return {"running": False}
        try:
            running = bool(client.is_running())
        except Exception:
            running = False
        return {"running": running, "model": self.model, "agent": self.agent}


def build_server_provider(settings) -> OpenCodeServerProvider:
    """Build a server provider from the JARVIS settings."""
    model = getattr(settings, "opencode_model", "") or DEFAULT_MODEL
    timeout = float(getattr(settings, "ai_timeout", 120) or 120)
    return OpenCodeServerProvider(model=model, timeout=timeout)
