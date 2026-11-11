"""jarvis_core.context — The shared context store.

Agents do not get the whole system. They get a reference to a shared context,
and the context holds only what the orchestrator decided they should see.
That keeps the data flow explicit and keeps an agent from reading everything.
"""

from __future__ import annotations

import threading
import time
import uuid
from typing import Any


class ContextStore:
    """Holds the shared task context that agents read and write."""

    def __init__(self):
        self._contexts: dict[str, dict[str, Any]] = {}
        self._lock = threading.RLock()

    def create(self, values: dict[str, Any] | None = None) -> str:
        reference = uuid.uuid4().hex[:12]
        with self._lock:
            self._contexts[reference] = {
                "values": dict(values or {}),
                "created": time.time(),
                "history": [],
            }
        return reference

    def get(self, reference: str) -> dict[str, Any]:
        with self._lock:
            entry = self._contexts.get(reference)
            if entry is None:
                return {}
            return dict(entry["values"])

    def set(self, reference: str, key: str, value: Any) -> None:
        with self._lock:
            entry = self._contexts.setdefault(
                reference, {"values": {}, "created": time.time(), "history": []},
            )
            entry["values"][key] = value
            entry["history"].append({"key": key, "at": time.time()})

    def update(self, reference: str, values: dict[str, Any]) -> None:
        with self._lock:
            entry = self._contexts.setdefault(
                reference, {"values": {}, "created": time.time(), "history": []},
            )
            entry["values"].update(values)

    def merge(self, reference: str, values: dict[str, Any]) -> None:
        """Merge the result of a task into the shared context."""
        if not values:
            return
        with self._lock:
            entry = self._contexts.setdefault(
                reference, {"values": {}, "created": time.time(), "history": []},
            )
            entry["values"].update(values)
            entry["history"].append({"key": "*", "at": time.time(), "size": len(values)})

    def snapshot(self, reference: str) -> dict:
        with self._lock:
            entry = self._contexts.get(reference)
            if entry is None:
                return {}
            return {
                "values": dict(entry["values"]),
                "created": entry["created"],
                "keys": list(entry["values"].keys()),
            }

    def release(self, reference: str) -> None:
        with self._lock:
            self._contexts.pop(reference, None)

    def count(self) -> int:
        with self._lock:
            return len(self._contexts)
