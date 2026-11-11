"""jarvis_cloud_providers — The cloud model providers.

These are the providers that need an account and a key. Each one declares
what it can do, so the router never sends a request to a model that cannot
answer it. They also report the tokens they used, so the usage shown in the
interface is measured rather than guessed.
"""

from __future__ import annotations

import json
import logging
import os
import time
import urllib.error
import urllib.request
from dataclasses import dataclass, field


log = logging.getLogger("jarvis.cloud")


class ProviderCapability:
    """What a provider can do."""

    TEXT = "text"
    VISION = "vision"
    AUDIO_INPUT = "audio_input"
    AUDIO_OUTPUT = "audio_output"
    TOOL_CALLING = "tool_calling"


@dataclass
class TokenUsage:
    """The tokens a provider reported."""

    prompt: int = 0
    completion: int = 0
    total: int = 0

    def add(self, other: "TokenUsage") -> None:
        self.prompt += other.prompt
        self.completion += other.completion
        self.total += other.total

    def to_dict(self) -> dict:
        return {"prompt": self.prompt, "completion": self.completion, "total": self.total}


@dataclass
class CloudReply:
    """The reply from a cloud provider."""

    text: str
    model: str
    usage: TokenUsage = field(default_factory=TokenUsage)
    latency_ms: int = 0


class CloudError(RuntimeError):
    """A provider failed. The message never contains the key."""


class CloudProvider:
    """The base class for the providers that call a hosted model."""

    name = "cloud"
    capabilities: set[str] = {ProviderCapability.TEXT}

    def __init__(self, model: str = "", timeout: float = 60.0):
        self.model = model
        self.timeout = max(5.0, min(float(timeout), 300.0))
        self.usage = TokenUsage()
        self.calls = 0
        self.failures = 0

    def installed(self) -> bool:
        return bool(self.api_key)

    @property
    def api_key(self) -> str:
        return ""

    def supports(self, capability: str) -> bool:
        return capability in self.capabilities

    def ask(self, prompt: str, context: str = "") -> CloudReply:
        raise NotImplementedError

    def stats(self) -> dict:
        return {
            "name": self.name,
            "model": self.model,
            "calls": self.calls,
            "failures": self.failures,
            "capabilities": sorted(self.capabilities),
            "tokens": self.usage.to_dict(),
        }


class OpenAIProvider(CloudProvider):
    """Calls the OpenAI chat completions endpoint."""

    name = "openai"
    capabilities = {
        ProviderCapability.TEXT,
        ProviderCapability.VISION,
        ProviderCapability.AUDIO_INPUT,
        ProviderCapability.TOOL_CALLING,
    }

    ENDPOINT = "https://api.openai.com/v1/chat/completions"

    def __init__(self, model: str = "", api_key: str = "", timeout: float = 60.0):
        super().__init__(model=model or "gpt-4o-mini", timeout=timeout)
        self._key = api_key or os.environ.get("OPENAI_API_KEY", "")

    @property
    def api_key(self) -> str:
        return self._key

    def ask(self, prompt: str, context: str = "") -> CloudReply:
        if not self._key:
            raise CloudError("OpenAI is not configured, so no request was sent.")
        messages: list[dict[str, str]] = []
        if context:
            messages.append({"role": "system", "content": context})
        messages.append({"role": "user", "content": prompt})
        body = json.dumps({
            "model": self.model,
            "messages": messages,
            "max_tokens": 700,
        }).encode("utf-8")
        return self._post(body)


class OpenRouterProvider(CloudProvider):
    """Calls the OpenRouter endpoint.

    The free model policy lives in the OpenRouter client, so this provider
    only has to speak the protocol.
    """

    name = "openrouter"
    capabilities = {ProviderCapability.TEXT, ProviderCapability.TOOL_CALLING}

    ENDPOINT = "https://openrouter.ai/api/v1/chat/completions"

    def __init__(self, model: str = "", api_key: str = "", timeout: float = 60.0):
        super().__init__(model=model, timeout=timeout)
        self._key = api_key or os.environ.get("OPENROUTER_API_KEY", "")

    @property
    def api_key(self) -> str:
        return self._key

    def ask(self, prompt: str, context: str = "") -> CloudReply:
        if not self._key:
            raise CloudError("OpenRouter is not configured, so no request was sent.")
        messages: list[dict[str, str]] = []
        if context:
            messages.append({"role": "system", "content": context})
        messages.append({"role": "user", "content": prompt})
        body = json.dumps({
            "model": self.model or "auto",
            "messages": messages,
            "max_tokens": 700,
        }).encode("utf-8")
        return self._post(body)

    def _post(self, body: bytes) -> CloudReply:
        request = urllib.request.Request(
            self.ENDPOINT,
            data=body,
            method="POST",
            headers={
                "Content-Type": "application/json",
                "Authorization": f"Bearer {self._key}",
                "HTTP-Referer": "https://github.com/kna070629-lang/jarvis-voice",
                "X-Title": "JARVIS Companion",
            },
        )
        started = time.perf_counter()
        self.calls += 1
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            self.failures += 1
            # The body may carry provider detail but never the key.
            raise CloudError(f"the provider returned HTTP {exc.code}") from None
        except Exception as exc:
            self.failures += 1
            raise CloudError(f"the provider could not be reached ({type(exc).__name__})") from None
        latency = int((time.perf_counter() - started) * 1000)
        choices = payload.get("choices") or []
        text = ""
        if choices:
            message = choices[0].get("message") or {}
            text = str(message.get("content") or "")
        if not text.strip():
            self.failures += 1
            raise CloudError("the provider returned no text")
        usage = _read_usage(payload.get("usage"))
        self.usage.add(usage)
        return CloudReply(text=text.strip(), model=str(payload.get("model") or self.model),
                          usage=usage, latency_ms=latency)


def _read_usage(raw) -> TokenUsage:
    if not isinstance(raw, dict):
        return TokenUsage()
    return TokenUsage(
        prompt=int(raw.get("prompt_tokens") or 0),
        completion=int(raw.get("completion_tokens") or 0),
        total=int(raw.get("total_tokens") or 0),
    )


class OpenAICompatibleProvider(CloudProvider):
    """Calls any endpoint that speaks the OpenAI chat protocol.

    This is how a self hosted model or a compatible gateway is added without
    touching the provider list.
    """

    name = "openai-compatible"

    def __init__(self, model: str = "", api_key: str = "", endpoint: str = "", timeout: float = 60.0):
        super().__init__(model=model, timeout=timeout)
        self._key = api_key or os.environ.get("JARVIS_COMPATIBLE_API_KEY", "")
        self.endpoint = endpoint or os.environ.get("JARVIS_COMPATIBLE_ENDPOINT", "")

    @property
    def api_key(self) -> str:
        return self._key

    def installed(self) -> bool:
        return bool(self.endpoint)

    def ask(self, prompt: str, context: str = "") -> CloudReply:
        if not self.endpoint:
            raise CloudError("No compatible endpoint is configured.")
        messages: list[dict[str, str]] = []
        if context:
            messages.append({"role": "system", "content": context})
        messages.append({"role": "user", "content": prompt})
        body = json.dumps({
            "model": self.model,
            "messages": messages,
            "max_tokens": 700,
        }).encode("utf-8")
        headers = {"Content-Type": "application/json"}
        if self._key:
            headers["Authorization"] = f"Bearer {self._key}"
        request = urllib.request.Request(self.endpoint, data=body, method="POST", headers=headers)
        started = time.perf_counter()
        self.calls += 1
        try:
            with urllib.request.urlopen(request, timeout=self.timeout) as response:
                payload = json.loads(response.read().decode("utf-8"))
        except urllib.error.HTTPError as exc:
            self.failures += 1
            raise CloudError(f"the endpoint returned HTTP {exc.code}") from None
        except Exception as exc:
            self.failures += 1
            raise CloudError(f"the endpoint could not be reached ({type(exc).__name__})") from None
        latency = int((time.perf_counter() - started) * 1000)
        choices = payload.get("choices") or []
        text = ""
        if choices:
            text = str((choices[0].get("message") or {}).get("content") or "")
        if not text.strip():
            self.failures += 1
            raise CloudError("the endpoint returned no text")
        usage = _read_usage(payload.get("usage"))
        self.usage.add(usage)
        return CloudReply(text=text.strip(), model=str(payload.get("model") or self.model),
                          usage=usage, latency_ms=latency)
