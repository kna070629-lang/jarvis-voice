"""Bounded, session-scoped SQLite conversation context (separate from long-term memory)."""

from __future__ import annotations

import hashlib
import os
import sqlite3
import stat
import time
from contextlib import contextmanager
from pathlib import Path

from jarvis_config import DEFAULT_CONVERSATION_PATH
from jarvis_memory import MemoryStore


class ConversationStore:
    def __init__(self, path: str | Path | None = None):
        self.path = Path(path or os.environ.get("JARVIS_CONVERSATION_PATH", DEFAULT_CONVERSATION_PATH)).expanduser()
        self.scope = "default"

    def set_scope(self, session_key: str) -> None:
        self.scope = hashlib.sha256(session_key.encode("utf-8")).hexdigest()[:24]

    def _connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        info = self.path.parent.lstat()
        if not stat.S_ISDIR(info.st_mode) or info.st_uid != os.getuid():
            raise PermissionError("conversation directory must be owned by JARVIS")
        if self.path.parent == Path.home() / ".local/share/jarvis":
            os.chmod(self.path.parent, 0o700)
        elif stat.S_IMODE(info.st_mode) & 0o077:
            raise PermissionError("custom conversation directory must have owner-only permissions")
        try:
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            file_info = self.path.lstat()
            if not stat.S_ISREG(file_info.st_mode) or file_info.st_uid != os.getuid():
                raise PermissionError("conversation database must be an owner-owned regular file")
            os.chmod(self.path, 0o600)
        else:
            os.close(fd)
        connection = sqlite3.connect(self.path, timeout=5)
        os.chmod(self.path, 0o600)
        connection.execute("PRAGMA busy_timeout=5000")
        connection.execute(
            "CREATE TABLE IF NOT EXISTS turns (id INTEGER PRIMARY KEY, scope TEXT NOT NULL, role TEXT NOT NULL CHECK(role IN ('user','assistant')), content TEXT NOT NULL, created_at INTEGER NOT NULL)"
        )
        connection.execute("CREATE INDEX IF NOT EXISTS turns_scope_id ON turns(scope,id DESC)")
        integrity = connection.execute("PRAGMA quick_check").fetchone()
        if not integrity or integrity[0] != "ok":
            connection.close()
            raise sqlite3.DatabaseError("conversation database failed its integrity check")
        connection.commit()
        return connection

    @contextmanager
    def _db(self):
        connection = self._connect()
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def append(self, role: str, content: str) -> bool:
        if role not in {"user", "assistant"} or not isinstance(content, str) or not content.strip():
            return False
        content = content.strip()[:12000]
        if MemoryStore.contains_secret(content):
            return False
        with self._db() as db:
            db.execute(
                "INSERT INTO turns(scope,role,content,created_at) VALUES(?,?,?,?)",
                (self.scope, role, content, int(time.time())),
            )
            db.execute(
                "DELETE FROM turns WHERE scope=? AND id NOT IN (SELECT id FROM turns WHERE scope=? ORDER BY id DESC LIMIT 40)",
                (self.scope, self.scope),
            )
        return True

    def history(self, *, limit: int = 12, max_chars: int = 10000) -> list[dict[str, str]]:
        limit = min(max(int(limit), 0), 40)
        with self._db() as db:
            rows = db.execute(
                "SELECT role,content FROM turns WHERE scope=? ORDER BY id DESC LIMIT ?",
                (self.scope, limit),
            ).fetchall()
        result: list[dict[str, str]] = []
        used = 0
        for role, content in reversed(rows):
            content = str(content)
            if used + len(content) > max_chars:
                continue
            result.append({"role": str(role), "content": content})
            used += len(content)
        return result
