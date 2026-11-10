"""Small, explicit, private SQLite memory for the mobile JARVIS assistant."""

from __future__ import annotations

import hashlib
import os
import re
import sqlite3
import stat
import time
from contextlib import contextmanager
from pathlib import Path


_SECRET_PATTERNS = (
    re.compile(r"\b(?:password|passcode|pin|api[ _-]?key|access[ _-]?token|refresh[ _-]?token|secret|credential)\b", re.I),
    re.compile(r"\b(?:sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,})\b"),
    # Opaque long tokens can arrive without a familiar vendor prefix.
    re.compile(r"\b[A-Za-z0-9_-]{40,}\b"),
    re.compile(r"-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----"),
)
_WORDS = re.compile(r"[a-z0-9]{2,}")
CLEAR_MEMORY_RE = re.compile(
    r"\b(?:clear|erase|delete)\s+(?:all\s+)?(?:my\s+)?mem(?:ory|ories)\b|"
    r"\bforget\s+(?:everything|all(?:\s+my\s+memories)?)\b",
    re.I,
)


class MemoryStore:
    """Stores only facts explicitly submitted through a remember command."""

    def __init__(self, path: str | Path | None = None):
        configured = os.environ.get("JARVIS_MEMORY_PATH")
        self.path = Path(path or configured or Path.home() / ".local/share/jarvis/memory.sqlite3")
        self.scope = "default"

    def _connect(self) -> sqlite3.Connection:
        self.path.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        parent_stat = self.path.parent.lstat()
        if not stat.S_ISDIR(parent_stat.st_mode) or parent_stat.st_uid != os.getuid():
            raise PermissionError("local memory directory must be a real directory owned by JARVIS")
        if self.path.parent == Path.home() / ".local/share/jarvis":
            os.chmod(self.path.parent, 0o700)
        elif stat.S_IMODE(parent_stat.st_mode) & 0o077:
            raise PermissionError("custom memory directory must have owner-only permissions")
        try:
            fd = os.open(self.path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        except FileExistsError:
            file_stat = self.path.lstat()
            if not stat.S_ISREG(file_stat.st_mode) or file_stat.st_uid != os.getuid():
                raise PermissionError("local memory database must be a regular file owned by JARVIS")
            os.chmod(self.path, 0o600)
        else:
            os.close(fd)
        connection = sqlite3.connect(self.path, timeout=5)
        os.chmod(self.path, 0o600)
        connection.execute("PRAGMA busy_timeout = 5000")
        connection.execute(
            """CREATE TABLE IF NOT EXISTS memories (
                id INTEGER PRIMARY KEY,
                scope TEXT NOT NULL,
                fact TEXT NOT NULL,
                normalized TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                UNIQUE(scope, normalized)
            )"""
        )
        connection.execute(
            "CREATE INDEX IF NOT EXISTS memories_scope_created ON memories(scope, created_at DESC)"
        )
        integrity = connection.execute("PRAGMA quick_check").fetchone()
        if not integrity or integrity[0] != "ok":
            connection.close()
            raise sqlite3.DatabaseError("local memory database failed its integrity check")
        connection.commit()
        return connection

    @contextmanager
    def _database(self):
        connection = self._connect()
        try:
            with connection:
                yield connection
        finally:
            connection.close()

    def set_scope(self, session_key: str) -> None:
        # Hash the session key so session identifiers are not persisted in the DB.
        self.scope = hashlib.sha256(session_key.encode("utf-8")).hexdigest()[:24]

    @staticmethod
    def _normalize(fact: str) -> str:
        return " ".join(fact.casefold().split())

    @staticmethod
    def contains_secret(fact: str) -> bool:
        return any(pattern.search(fact) for pattern in _SECRET_PATTERNS)

    def save(self, fact: str) -> tuple[bool, str]:
        fact = " ".join(fact.split()).strip(" \t\r\n.,;:")
        if not fact:
            return False, "Tell me the fact or preference you want me to remember."
        if len(fact) > 1000:
            return False, "That memory is too long; please keep it under 1,000 characters."
        if self.contains_secret(fact):
            return False, "I did not save that because it looks like a password, token, or private key."
        normalized = self._normalize(fact)
        with self._database() as db:
            cursor = db.execute(
                "INSERT OR IGNORE INTO memories(scope, fact, normalized, created_at) VALUES (?, ?, ?, ?)",
                (self.scope, fact, normalized, int(time.time())),
            )
            if cursor.rowcount:
                return True, "I saved that to your local JARVIS memory."
            return True, "I already have that in your local JARVIS memory."

    def list_memories(self) -> list[tuple[int, str]]:
        with self._database() as db:
            rows = db.execute(
                "SELECT id, fact FROM memories WHERE scope=? ORDER BY created_at DESC, id DESC LIMIT 100",
                (self.scope,),
            ).fetchall()
        return [(int(row[0]), str(row[1])) for row in rows]

    def delete_id(self, memory_id: int) -> bool:
        with self._database() as db:
            cursor = db.execute("DELETE FROM memories WHERE scope=? AND id=?", (self.scope, memory_id))
            return cursor.rowcount > 0

    def delete_matching(self, phrase: str) -> int:
        needle = self._normalize(phrase)
        if not needle:
            return 0
        with self._database() as db:
            rows = db.execute("SELECT id, normalized FROM memories WHERE scope=?", (self.scope,)).fetchall()
            ids = [int(row[0]) for row in rows if needle in str(row[1]) or str(row[1]) in needle]
            if ids:
                placeholders = ",".join("?" for _ in ids)
                db.execute(
                    f"DELETE FROM memories WHERE scope=? AND id IN ({placeholders})",
                    (self.scope, *ids),
                )
            return len(ids)

    def clear(self) -> int:
        with self._database() as db:
            cursor = db.execute("DELETE FROM memories WHERE scope=?", (self.scope,))
            return cursor.rowcount

    def retrieve(self, query: str, *, limit: int = 5, max_chars: int = 1800) -> list[str]:
        terms = set(_WORDS.findall(query.casefold())) - {
            "the", "and", "for", "with", "that", "this", "what", "when", "where", "how",
            "about", "you", "your", "are", "was", "were", "have", "has", "does", "can",
            "could", "would", "will", "tell", "please", "jarvis", "remember", "forget",
        }
        if not terms:
            return []
        memories = self.list_memories()
        scored = []
        for order, (memory_id, fact) in enumerate(memories):
            fact_terms = set(_WORDS.findall(fact.casefold()))
            overlap = terms & fact_terms
            if overlap:
                scored.append((len(overlap) / max(1, len(terms)), -order, memory_id, fact))
        scored.sort(reverse=True)
        result: list[str] = []
        used = 0
        for _, _, memory_id, fact in scored[:limit]:
            line = f"[{memory_id}] {fact}"
            if used + len(line) > max_chars:
                continue
            result.append(line)
            used += len(line)
        return result


def handle_memory_command(store: MemoryStore, text: str, *, confirm_clear: bool = False) -> str | None:
    """Handle explicit memory management phrases; return None for normal chat."""
    message = text.strip()
    lowered = message.casefold()

    if CLEAR_MEMORY_RE.search(lowered):
        if not confirm_clear:
            return "Clearing memory deletes all explicitly saved facts for this session. Say ‘confirm clear my memory’ to proceed."
        count = store.clear()
        return f"I cleared {count} saved memor{'y' if count == 1 else 'ies'} from this session's local memory."

    if re.search(r"\b(?:what\s+do\s+you\s+remember|list\s+(?:my\s+)?memories|show\s+(?:my\s+)?memories)\b", lowered):
        rows = store.list_memories()
        if not rows:
            return "I don't have any saved memories for this session."
        return "Here is what I have saved locally:\n" + "\n".join(f"{memory_id}. {fact}" for memory_id, fact in rows)

    match = re.search(r"^(?:please\s+)?remember\s+(?:this|that)\s*[:,-]?\s*(.+)$", message, re.I)
    if not match:
        match = re.search(r"^(?:please\s+)?remember\s+(.+)$", message, re.I)
    if match:
        return store.save(match.group(1))[1]

    match = re.search(r"^forget\s+(?:memory\s+)?#?(\d+)\s*$", lowered)
    if match:
        deleted = store.delete_id(int(match.group(1)))
        return "I deleted that memory." if deleted else "I couldn't find that memory."

    match = re.search(r"^(?:please\s+)?forget\s+(?:this\s*[:,-]?\s*|that\s+|about\s+)(.+)$", message, re.I)
    if match:
        count = store.delete_matching(match.group(1).strip(" .!?"))
        return f"I deleted {count} matching memor{'y' if count == 1 else 'ies'}." if count else "I couldn't find a matching saved memory."
    if lowered in {"forget this", "forget that"}:
        rows = store.list_memories()
        if rows and store.delete_id(rows[0][0]):
            return "I deleted the most recently saved memory."
        return "There is no saved memory to delete."

    return None
