"""Private configuration loading for the Python-first JARVIS runtime."""

from __future__ import annotations

import json
import os
import stat
from dataclasses import dataclass, field
from pathlib import Path


CONFIG_DIR = Path.home() / ".config" / "jarvis"
CONFIG_PATH = CONFIG_DIR / "secrets.json"
DEFAULT_MODEL = "google/gemma-4-31b-it:free"
DEFAULT_AI_PRIMARY = "opencode-server"
DEFAULT_AI_FALLBACK = "opencode"
DEFAULT_AI_TIMEOUT = 60.0
DEFAULT_MEMORY_PATH = Path.home() / ".local" / "share" / "jarvis" / "memory.sqlite3"
DEFAULT_CONVERSATION_PATH = Path.home() / ".local" / "share" / "jarvis" / "conversations.sqlite3"


@dataclass(frozen=True)
class JarvisSettings:
    # Legacy OpenRouter fields remain readable for old configs, but they are no
    # longer used by the chat path. The active providers are the local agent
    # CLIs selected by ai_primary and ai_fallback.
    api_key: str = field(default="", repr=False)
    model: str = DEFAULT_MODEL
    online_enabled: bool = True
    ai_enabled: bool = True
    ai_primary: str = DEFAULT_AI_PRIMARY
    ai_fallback: str = DEFAULT_AI_FALLBACK
    ai_timeout: float = DEFAULT_AI_TIMEOUT
    codex_model: str = ""
    opencode_model: str = ""
    memory_path: Path = DEFAULT_MEMORY_PATH
    conversation_path: Path = DEFAULT_CONVERSATION_PATH
    request_timeout: float = 35.0
    model_catalog_ttl: int = 1800


def _read_private_config(path: Path = CONFIG_PATH) -> dict:
    directory = path.parent
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    parent_info = directory.lstat()
    if not stat.S_ISDIR(parent_info.st_mode) or parent_info.st_uid != os.getuid():
        raise PermissionError("JARVIS config directory must be an owner-owned directory")
    os.chmod(directory, 0o700)
    try:
        info = path.lstat()
    except FileNotFoundError:
        return {}
    if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
        raise PermissionError("JARVIS config must be an owner-owned regular file")
    os.chmod(path, 0o600)
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as exc:
        raise ValueError("JARVIS private config is unreadable or invalid JSON") from exc
    if not isinstance(value, dict):
        raise ValueError("JARVIS private config must contain a JSON object")
    return value


def load_settings() -> JarvisSettings:
    config = _read_private_config()
    api_key = os.environ.get("OPENROUTER_API_KEY") or os.environ.get("JARVIS_OPENROUTER_API_KEY") or config.get("openrouter_api_key", "")
    if not isinstance(api_key, str):
        api_key = ""
    model = os.environ.get("OPENROUTER_MODEL") or config.get("openrouter_model") or DEFAULT_MODEL
    mode = os.environ.get("JARVIS_MODE", "online").strip().casefold()
    online_enabled = mode not in {"offline", "off", "0", "false"}
    ai_enabled = os.environ.get("JARVIS_AI_ENABLED", "1").strip().casefold() not in {"0", "false", "off", "no"}
    ai_primary = os.environ.get("JARVIS_AI_PRIMARY") or config.get("ai_primary") or DEFAULT_AI_PRIMARY
    ai_fallback = os.environ.get("JARVIS_AI_FALLBACK") or config.get("ai_fallback") or DEFAULT_AI_FALLBACK
    try:
        ai_timeout = float(os.environ.get("JARVIS_AI_TIMEOUT") or config.get("ai_timeout") or DEFAULT_AI_TIMEOUT)
    except (TypeError, ValueError):
        ai_timeout = DEFAULT_AI_TIMEOUT
    codex_model = os.environ.get("JARVIS_CODEX_MODEL") or config.get("codex_model") or ""
    opencode_model = os.environ.get("JARVIS_OPENCODE_MODEL") or config.get("opencode_model") or ""
    memory_path = Path(os.environ.get("JARVIS_MEMORY_PATH", str(DEFAULT_MEMORY_PATH))).expanduser()
    conversation_path = Path(os.environ.get("JARVIS_CONVERSATION_PATH", str(DEFAULT_CONVERSATION_PATH))).expanduser()
    return JarvisSettings(
        api_key=api_key.strip(),
        model=str(model).strip(),
        online_enabled=online_enabled,
        ai_enabled=ai_enabled,
        ai_primary=str(ai_primary).strip().casefold(),
        ai_fallback=str(ai_fallback).strip().casefold(),
        ai_timeout=max(5.0, min(ai_timeout, 180.0)),
        codex_model=str(codex_model).strip(),
        opencode_model=str(opencode_model).strip(),
        memory_path=memory_path,
        conversation_path=conversation_path,
    )
