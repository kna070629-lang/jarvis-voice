"""Interactive local setup helpers. Secrets are read without terminal echo."""

from __future__ import annotations

import getpass
import json
import os
import shutil
import stat
import tempfile
from datetime import datetime, timezone
from pathlib import Path

from jarvis_config import CONFIG_DIR, CONFIG_PATH, _read_private_config


def save_openrouter_key(api_key: str, path: Path = CONFIG_PATH) -> None:
    api_key = api_key.strip()
    if len(api_key) < 20 or any(ch.isspace() for ch in api_key):
        raise ValueError("The key format is invalid; no value was saved.")
    path = Path(path)
    path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    parent_info = path.parent.lstat()
    if not stat.S_ISDIR(parent_info.st_mode) or parent_info.st_uid != os.getuid():
        raise PermissionError("JARVIS config directory must be owner-owned")
    os.chmod(path.parent, 0o700)
    data = _read_private_config(path)
    data["openrouter_api_key"] = api_key
    if path.exists():
        info = path.lstat()
        if not stat.S_ISREG(info.st_mode) or info.st_uid != os.getuid():
            raise PermissionError("JARVIS config must be an owner-owned regular file")
        backup_dir = path.parent / "backups"
        backup_dir.mkdir(mode=0o700, exist_ok=True)
        os.chmod(backup_dir, 0o700)
        stamp = datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ")
        backup_path = backup_dir / f"secrets-{stamp}.json"
        if backup_path.exists():
            raise FileExistsError("Refusing to overwrite an existing private config backup")
        shutil.copy2(path, backup_path)
        os.chmod(backup_path, 0o600)
    fd, temp_name = tempfile.mkstemp(prefix=".secrets-", dir=path.parent)
    try:
        os.fchmod(fd, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            json.dump(data, stream, indent=2)
            stream.write("\n")
            stream.flush()
            os.fsync(stream.fileno())
        os.replace(temp_name, path)
        os.chmod(path, 0o600)
    except Exception:
        try:
            os.unlink(temp_name)
        except OSError:
            pass
        raise


def main() -> int:
    import argparse

    parser = argparse.ArgumentParser(description="Securely configure local JARVIS providers")
    parser.add_argument("command", choices=("configure-openrouter",))
    args = parser.parse_args()
    if args.command == "configure-openrouter":
        try:
            key = getpass.getpass("OpenRouter API key (input hidden): ")
            save_openrouter_key(key)
        except (ValueError, OSError, PermissionError) as exc:
            print(f"Configuration failed: {exc}")
            return 1
        print(f"OpenRouter key saved to {CONFIG_PATH} with owner-only file permissions.")
        print("The configured model is still checked against live zero-cost metadata before each request.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
