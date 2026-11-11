"""jarvis_core.engines.model_router — The model router.

One routing surface for every model provider, so no agent has to know which
provider is in use. The router tracks which models answered, how many tokens
were used and how failures were handled.
"""

from __future__ import annotations

import logging
import threading
import time
from dataclasses import dataclass, field

from ..messages import Message, Status


log = logging.getLogger("jarvis.core.model_router")


@dataclass
class ModelCall:
    """One record of a model request."""

    provider: str
    model: str
    ok: bool
    duration_ms: int
    error: str = ""
    at: float = field(default_factory=time.time)


class ModelRouter:
    """Routes a request to the first provider that can answer it.

    The providers are tried in order. A provider that fails or is not
    installed moves to the next one, so a single outage does not take the
    assistant down.
    """

    def __init__(self, providers: list | None = None):
        self._providers: list = list(providers or [])
        self._lock = threading.RLock()
        self._history: list[ModelCall] = []
        self._last_provider = ""
        self._last_model = ""
        self._tokens = {"prompt": 0, "completion": 0, "total": 0}

    def add_provider(self, provider) -> None:
        with self._lock:
            self._providers.append(provider)

    def providers(self) -> list:
        with self._lock:
            return list(self._providers)

    @property
    def last_provider(self) -> str:
        return self._last_provider

    @property
    def last_model(self) -> str:
        return self._last_model

    def available(self) -> list[dict]:
        """The providers and whether they can be used right now."""
        report = []
        for provider in self.providers():
            name = getattr(provider, "name", provider.__class__.__name__)
            installed = True
            try:
                checker = getattr(provider, "installed", None)
                if callable(checker):
                    installed = bool(checker())
            except Exception:
                installed = False
            report.append({
                "name": name,
                "installed": installed,
                "model": getattr(provider, "model", ""),
            })
        return report

    def complete(self, prompt: str, context: str = "", capability: str = "") -> dict:
        """Ask the first provider that answers.

        When [capability] is given, a provider that does not support it is
        skipped, so a request is never sent to a model that cannot do it.

        Returns a dict with the text, the provider that answered and the
        list of failures for the ones that did not.
        """
        failures: list[str] = []
        for provider in self.providers():
            name = getattr(provider, "name", provider.__class__.__name__)
            installed = True
            try:
                checker = getattr(provider, "installed", None)
                if callable(checker):
                    installed = bool(checker())
            except Exception:
                installed = False
            if not installed:
                failures.append(f"{name}: not installed")
                self._record(name, getattr(provider, "model", ""), False, 0, "not installed")
                continue
            if capability:
                supports = getattr(provider, "supports", None)
                if callable(supports) and not supports(capability):
                    failures.append(f"{name}: does not support {capability}")
                    self._record(name, getattr(provider, "model", ""), False, 0, "no capability")
                    continue
            started = time.perf_counter()
            try:
                reply = provider.ask(prompt, context) if context else provider.ask(prompt)
            except Exception as exc:
                duration = int((time.perf_counter() - started) * 1000)
                detail = type(exc).__name__
                failures.append(f"{name}: {detail}")
                self._record(name, getattr(provider, "model", ""), False, duration, detail)
                log.warning("The model provider %s failed: %s", name, detail)
                continue
            duration = int((time.perf_counter() - started) * 1000)
            text = getattr(reply, "text", reply)
            if not isinstance(text, str) or not text.strip():
                failures.append(f"{name}: empty answer")
                self._record(name, getattr(provider, "model", ""), False, duration, "empty answer")
                continue
            provider_name = getattr(reply, "provider", name)
            self._record(provider_name, getattr(provider, "model", ""), True, duration, "")
            self._add_tokens(provider)
            with self._lock:
                self._last_provider = provider_name
                self._last_model = getattr(provider, "model", "")
            return {
                "text": text.strip(),
                "provider": provider_name,
                "model": getattr(provider, "model", ""),
                "duration_ms": duration,
                "failures": failures,
            }
        return {
            "text": "",
            "provider": "",
            "model": "",
            "duration_ms": 0,
            "failures": failures,
            "error": "no provider could answer",
        }

    def complete_stream(self, prompt: str, context: str = "", on_delta=None) -> dict:
        """Stream an answer from the first provider that supports it."""
        for provider in self.providers():
            ask_stream = getattr(provider, "ask_stream", None)
            if not callable(ask_stream):
                continue
            started = time.perf_counter()
            try:
                text = ask_stream(prompt, context, on_delta) if on_delta else ask_stream(prompt, context)
            except Exception as exc:
                duration = int((time.perf_counter() - started) * 1000)
                self._record(getattr(provider, "name", "?"), getattr(provider, "model", ""), False, duration, type(exc).__name__)
                continue
            if not isinstance(text, str) or not text.strip():
                continue
            duration = int((time.perf_counter() - started) * 1000)
            name = getattr(provider, "name", provider.__class__.__name__)
            self._record(name, getattr(provider, "model", ""), True, duration, "")
            with self._lock:
                self._last_provider = name
                self._last_model = getattr(provider, "model", "")
            return {
                "text": text.strip(),
                "provider": name,
                "model": getattr(provider, "model", ""),
                "duration_ms": duration,
            }
        return self.complete(prompt, context)

    def _add_tokens(self, provider) -> None:
        """Take the token count the provider reported, when it reports one."""
        usage = getattr(provider, "usage", None)
        if usage is None:
            return
        with self._lock:
            self._tokens["prompt"] += int(getattr(usage, "prompt", 0) or 0)
            self._tokens["completion"] += int(getattr(usage, "completion", 0) or 0)
            self._tokens["total"] += int(getattr(usage, "total", 0) or 0)

    def tokens(self) -> dict:
        with self._lock:
            return dict(self._tokens)

    def _record(self, provider: str, model: str, ok: bool, duration_ms: int, error: str) -> None:
        with self._lock:
            self._history.append(ModelCall(provider, model, ok, duration_ms, error))
            del self._history[:-100]

    def stats(self) -> dict:
        with self._lock:
            history = list(self._history)
        successful = [call for call in history if call.ok]
        return {
            "calls": len(history),
            "successful": len(successful),
            "failed": len(history) - len(successful),
            "last_provider": self._last_provider,
            "last_model": self._last_model,
            "providers": self.available(),
            "tokens": self.tokens(),
            "average_ms": int(sum(call.duration_ms for call in successful) / len(successful)) if successful else 0,
        }

    def recent_calls(self, limit: int = 20) -> list[dict]:
        with self._lock:
            calls = list(self._history)[-limit:]
        return [
            {
                "provider": call.provider,
                "model": call.model,
                "ok": call.ok,
                "duration_ms": call.duration_ms,
                "error": call.error,
                "at": call.at,
            }
            for call in calls
        ]
