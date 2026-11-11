"""jarvis_core.engines.memory — The memory engine.

Short term conversation state and long term saved facts live here. The engine
retrieves the context that is relevant to a request and writes back what the
user asked to remember.
"""

from __future__ import annotations

import re
import threading
import time
from typing import Any

from ..messages import Status


class MemoryEngine:
    """Holds the working state and the long term memory."""

    def __init__(self, session_key: str = "jarvis-mobile", max_turns: int = 40):
        self.session_key = session_key
        self._turns: list[dict] = []
        self._facts: list[dict] = []
        self._lock = threading.RLock()
        self._max_turns = max_turns

    # -- conversation ------------------------------------------------------
    def record(self, role: str, content: str, reference: str = "") -> None:
        with self._lock:
            self._turns.append({
                "role": role,
                "content": content,
                "reference": reference,
                "at": time.time(),
            })
            del self._turns[:-self._max_turns]

    def history(self, limit: int = 12, max_chars: int = 8000) -> list[dict]:
        with self._lock:
            turns = list(self._turns)[-limit:]
        total = 0
        trimmed: list[dict] = []
        for turn in reversed(turns):
            size = len(turn["content"])
            if total + size > max_chars:
                break
            total += size
            trimmed.append(turn)
        return list(reversed(trimmed))

    def last_utterance(self) -> str:
        with self._lock:
            for turn in reversed(self._turns):
                if turn["role"] == "user":
                    return turn["content"]
            return ""

    # -- long term ---------------------------------------------------------
    def remember(self, fact: str, source: str = "user") -> str:
        """Save a fact. The same fact is not saved twice."""
        text = " ".join(str(fact).split()).strip()
        if not text:
            return ""
        with self._lock:
            for existing in self._facts:
                if existing["fact"].lower() == text.lower():
                    return existing["id"]
            identifier = f"mem_{len(self._facts) + 1}_{int(time.time()) % 100000}"
            self._facts.append({
                "id": identifier,
                "fact": text,
                "source": source,
                "at": time.time(),
            })
        return identifier

    def forget(self, identifier: str) -> bool:
        with self._lock:
            before = len(self._facts)
            self._facts = [fact for fact in self._facts if fact["id"] != identifier]
            return len(self._facts) != before

    def facts(self) -> list[dict]:
        with self._lock:
            return list(self._facts)

    def retrieve(self, query: str, limit: int = 5) -> list[str]:
        """Return the facts that are relevant to a query."""
        with self._lock:
            facts = list(self._facts)
        if not facts:
            return []
        words = {word for word in re.findall(r"[a-z0-9]+", query.lower()) if len(word) > 2}
        scored: list[tuple[int, str]] = []
        for fact in facts:
            text = fact["fact"].lower()
            score = 0
            if words and any(word in text for word in words):
                score += 2
            for word in words:
                if word in text:
                    score += 1
            if score:
                scored.append((score, fact["fact"]))
        scored.sort(key=lambda item: -item[0])
        return [fact for _, fact in scored[:limit]]

    # -- state -------------------------------------------------------------
    def state(self) -> dict:
        with self._lock:
            return {
                "session": self.session_key,
                "turns": len(self._turns),
                "facts": len(self._facts),
            }

    def clear_session(self) -> None:
        with self._lock:
            self._turns.clear()
