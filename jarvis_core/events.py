"""jarvis_core.events — The event bus and the connection tracker.

The bus distributes events to the engines that care about them. The tracker
keeps the live state of every connection in the network, which is what the
interface draws.
"""

from __future__ import annotations

import threading
import time
from collections import deque
from typing import Callable

from .messages import LinkState, Message


class EventBus:
    """Distributes messages to interested subscribers."""

    def __init__(self, history: int = 200):
        self._subscribers: list[tuple[str, Callable[[Message], None]]] = []
        self._lock = threading.RLock()
        self._history: deque[Message] = deque(maxlen=history)

    def subscribe(self, topic: str, handler: Callable[[Message], None]) -> None:
        with self._lock:
            self._subscribers.append((topic, handler))

    def publish(self, message: Message) -> None:
        with self._lock:
            self._history.append(message)
            handlers = [
                handler
                for topic, handler in self._subscribers
                if topic in ("*", message.task_type, message.agent_id, message.destination)
            ]
        for handler in handlers:
            try:
                handler(message)
            except Exception:
                continue

    def history(self, limit: int = 40) -> list[Message]:
        with self._lock:
            return list(self._history)[-limit:]

    def clear(self) -> None:
        with self._lock:
            self._history.clear()


class ConnectionTracker:
    """Keeps the live state of the connections between nodes.

    The links are declared by the architecture, so the shape of the network is
    fixed. What changes is their state, which is driven by the real traffic.
    """

    def __init__(self):
        self._links: dict[tuple[str, str], LinkState] = {}
        self._lock = threading.RLock()

    def declare(self, source: str, destination: str) -> None:
        with self._lock:
            key = (source, destination)
            if key not in self._links:
                self._links[key] = LinkState(source=source, destination=destination)

    def declare_many(self, pairs: list[tuple[str, str]]) -> None:
        for source, destination in pairs:
            self.declare(source, destination)

    def links(self) -> list[LinkState]:
        with self._lock:
            return list(self._links.values())

    def set_status(
        self,
        source: str,
        destination: str,
        status: str,
        message: Message | None = None,
    ) -> None:
        with self._lock:
            key = (source, destination)
            link = self._links.get(key)
            if link is None:
                link = LinkState(source=source, destination=destination)
                self._links[key] = link
            link.status = status
            if message is not None:
                link.transmissions += 1
                link.last_message_id = message.id
                link.last_task_id = message.task_id

    def record_failure(self, source: str, destination: str) -> None:
        with self._lock:
            link = self._links.get((source, destination))
            if link is not None:
                link.failures += 1
                link.status = "failed"

    def settle(self, source: str, destination: str, ok: bool) -> None:
        """Move a link back to a resting state after a transmission."""
        with self._lock:
            link = self._links.get((source, destination))
            if link is None:
                return
            link.status = "successful" if ok else "failed"
            link.last_activity = time.time() if hasattr(link, "last_activity") else None
