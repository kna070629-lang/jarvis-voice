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
DEFAULT_MEMORY_PATH = Path.home() / ".local" / "share" / "jarvis" / "memory.sqlite3"
DEFAULT_CONVERSATION_PATH = Path.home() / ".local" / "share" / "jarvis" / "conversations.sqlite3"


@dataclass(frozen=True)
class JarvisSettings:
    api_key: str = field(default="", repr=False)
    model: str = DEFAULT_MODEL
    online_enabled: bool = True
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
    memory_path = Path(os.environ.get("JARVIS_MEMORY_PATH", str(DEFAULT_MEMORY_PATH))).expanduser()
    conversation_path = Path(os.environ.get("JARVIS_CONVERSATION_PATH", str(DEFAULT_CONVERSATION_PATH))).expanduser()
    return JarvisSettings(
        api_key=api_key.strip(),
        model=str(model).strip(),
        online_enabled=online_enabled,
        memory_path=memory_path,
        conversation_path=conversation_path,
    )
