"""OpenRouter chat client with live verification and a strict free-only policy."""

from __future__ import annotations

import json
import time
from dataclasses import dataclass
from decimal import Decimal, InvalidOperation
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from jarvis_config import JarvisSettings


API_BASE = "https://openrouter.ai/api/v1"
MAX_ATTEMPTS = 1  # Do not replay an ambiguous chat request or consume extra free quota.


class OpenRouterError(RuntimeError):
    """A safe-to-display OpenRouter failure with no response body or key."""


class FreeModelUnavailable(OpenRouterError):
    pass


class OpenRouterRateLimited(OpenRouterError):
    pass


@dataclass(frozen=True)
class ModelInfo:
    model_id: str
    context_length: int


class OpenRouterClient:
    def __init__(self, settings: JarvisSettings, *, opener=urlopen, clock=time.monotonic):
        self.settings = settings
        self._opener = opener
        self._clock = clock
        self._catalog: dict[str, ModelInfo] = {}
        self._catalog_loaded = 0.0

    @staticmethod
    def _is_zero(value) -> bool:
        try:
            return Decimal(str(value)) == 0
        except (InvalidOperation, TypeError, ValueError):
            return False

    def free_models(self, *, force_refresh: bool = False) -> dict[str, ModelInfo]:
        if self._catalog and not force_refresh and self._clock() - self._catalog_loaded < self.settings.model_catalog_ttl:
            return dict(self._catalog)
        headers = {"Accept": "application/json", "User-Agent": "JARVIS-Python/1.0"}
        if self.settings.api_key:
            headers["Authorization"] = "Bearer " + self.settings.api_key
        request = Request(API_BASE + "/models", headers=headers, method="GET")
        try:
            with self._opener(request, timeout=min(12, self.settings.request_timeout)) as response:
                if response.status != 200:
                    raise OpenRouterError("OpenRouter model catalog is temporarily unavailable.")
                payload = json.loads(response.read(5_000_000).decode("utf-8"))
        except HTTPError as exc:
            if exc.code == 429:
                raise OpenRouterRateLimited("OpenRouter model catalog is rate limited; try again later.") from None
            raise OpenRouterError(f"OpenRouter model catalog returned HTTP {exc.code}.") from None
        except (URLError, TimeoutError, OSError, json.JSONDecodeError, UnicodeDecodeError):
            raise OpenRouterError("Could not verify current OpenRouter free-model pricing.") from None

        rows = payload.get("data") if isinstance(payload, dict) else None
        if not isinstance(rows, list):
            raise OpenRouterError("OpenRouter returned an invalid model catalog.")
        catalog: dict[str, ModelInfo] = {}
        for row in rows:
            if not isinstance(row, dict):
                continue
            model_id = row.get("id")
            pricing = row.get("pricing")
            if (
                isinstance(model_id, str)
                and model_id.endswith(":free")
                and isinstance(pricing, dict)
                and self._is_zero(pricing.get("prompt"))
                and self._is_zero(pricing.get("completion"))
            ):
                try:
                    context = max(1024, int(row.get("context_length") or 4096))
                except (TypeError, ValueError):
                    context = 4096
                catalog[model_id] = ModelInfo(model_id, context)
        self._catalog = catalog
        self._catalog_loaded = self._clock()
        return dict(catalog)

    def verified_model(self) -> ModelInfo:
        model = self.settings.model
        # The free router alias is intentionally rejected: JARVIS pins one
        # explicit :free model instead of delegating model selection.
        if not model.endswith(":free") or model == "openrouter/free":
            raise FreeModelUnavailable("Configured model is not an explicit free-model variant; no request was sent.")
        # Recheck the public catalog immediately before every paid-capable
        # endpoint call; never trust stale pricing metadata for a chat request.
        catalog = self.free_models(force_refresh=True)
        if model not in catalog:
            choices = ", ".join(sorted(catalog)[:8])
            suffix = f" Available verified free models include: {choices}." if choices else " No free models were verified."
            raise FreeModelUnavailable("Configured model is not currently listed with zero input and output pricing; no request was sent." + suffix)
        return catalog[model]

    def chat(self, messages: list[dict[str, str]], *, max_output_tokens: int = 700) -> str:
        if not self.settings.online_enabled:
            raise OpenRouterError("JARVIS is in offline mode; no OpenRouter request was sent.")
        if not self.settings.api_key:
            raise OpenRouterError("OpenRouter is not configured; no cloud request was sent.")
        model = self.verified_model()
        body = json.dumps({
            "model": model.model_id,
            "messages": messages,
            "max_tokens": min(max_output_tokens, 1200),
            "temperature": 0.4,
            "provider": {"allow_fallbacks": False},
        }).encode("utf-8")
        request = Request(
            API_BASE + "/chat/completions",
            data=body,
            headers={
                "Authorization": "Bearer " + self.settings.api_key,
                "Content-Type": "application/json",
                "Accept": "application/json",
                "HTTP-Referer": "https://github.com/strognoff/jarvis-voice",
                "X-Title": "JARVIS Python Voice Assistant",
            },
            method="POST",
        )
        try:
            with self._opener(request, timeout=self.settings.request_timeout) as response:
                if response.status != 200:
                    raise OpenRouterError(f"OpenRouter returned HTTP {response.status}.")
                payload = json.loads(response.read(2_000_000).decode("utf-8"))
        except HTTPError as exc:
            if exc.code == 429:
                raise OpenRouterRateLimited("OpenRouter free-model rate limit reached; no paid fallback was attempted.") from None
            if exc.code in {401, 403}:
                raise OpenRouterError("OpenRouter rejected the configured key or model access.") from None
            if exc.code in {400, 413, 422}:
                raise OpenRouterError("OpenRouter rejected the request or its context size.") from None
            raise OpenRouterError(f"OpenRouter request failed with HTTP {exc.code}; no paid fallback was attempted.") from None
        except TimeoutError:
            raise OpenRouterError("OpenRouter request timed out; it was not retried to avoid duplicate requests.") from None
        except (URLError, OSError, json.JSONDecodeError, UnicodeDecodeError):
            raise OpenRouterError("Could not connect to OpenRouter; no provider fallback was attempted.") from None
        try:
            content = payload["choices"][0]["message"]["content"]
        except (KeyError, IndexError, TypeError):
            raise OpenRouterError("OpenRouter returned an unexpected response shape.") from None
        if isinstance(content, list):
            content = "\n".join(str(item.get("text", "")) for item in content if isinstance(item, dict))
        if not isinstance(content, str) or not content.strip():
            raise OpenRouterError("OpenRouter returned an empty assistant response.")
        return content.strip()
