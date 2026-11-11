"""jarvis_core.engines.comms — The context and communication engine.

The engine owns the shared context and the routing of structured messages
between agents. Agents never talk to each other directly: they send a
message to the engine and the engine decides who receives it.
"""

from __future__ import annotations

import logging
import threading
import time
from typing import Callable

from ..context import ContextStore
from ..events import ConnectionTracker, EventBus
from ..messages import Message, Status


log = logging.getLogger("jarvis.core.comms")


class CommunicationEngine:
    """Routes structured messages and owns the shared context."""

    def __init__(
        self,
        context_store: ContextStore | None = None,
        bus: EventBus | None = None,
        tracker: ConnectionTracker | None = None,
    ):
        self.context = context_store or ContextStore()
        self.bus = bus or EventBus()
        self.tracker = tracker or ConnectionTracker()
        self._handlers: dict[str, Callable[[Message], Message]] = {}
        self._lock = threading.RLock()
        self._sent = 0
        self._received = 0
        self._dropped = 0

    # -- routing -----------------------------------------------------------
    def register_route(self, destination: str, handler: Callable[[Message], Message]) -> None:
        """Register what happens when a message arrives at a destination."""
        with self._lock:
            self._handlers[destination] = handler

    def route(self, message: Message) -> Message | None:
        """Deliver a message to its destination.

        The message is rejected when the destination is not registered, so a
        model cannot invent a target.
        """
        with self._lock:
            handler = self._handlers.get(message.destination)
        self.tracker.set_status(message.source, message.destination, "transmitting", message)
        if handler is None:
            self._dropped += 1
            self.tracker.record_failure(message.source, message.destination)
            log.warning("No route to %s for task %s", message.destination, message.task_id)
            return None
        try:
            reply = handler(message)
        except Exception as exc:
            self._dropped += 1
            self.tracker.record_failure(message.source, message.destination)
            log.exception("The route to %s failed", message.destination)
            reply = message.copy()
            reply.status = Status.FAILED
            reply.error = type(exc).__name__
            return reply
        self._received += 1
        self.tracker.settle(message.source, message.destination, reply.status != Status.FAILED)
        self.bus.publish(reply)
        return reply

    def send(self, message: Message) -> Message | None:
        """Send a message and record the transmission."""
        self._sent += 1
        return self.route(message)

    # -- shared context ----------------------------------------------------
    def new_context(self, values: dict | None = None) -> str:
        return self.context.create(values)

    def share(self, reference: str, values: dict) -> None:
        """Publish results into the shared context."""
        self.context.merge(reference, values)

    def read(self, reference: str, keys: list[str] | None = None) -> dict:
        """Read the shared context, optionally only some keys."""
        values = self.context.get(reference)
        if keys is None:
            return values
        return {key: values[key] for key in keys if key in values}

    # -- observation -------------------------------------------------------
    def stats(self) -> dict:
        return {
            "sent": self._sent,
            "received": self._received,
            "dropped": self._dropped,
            "routes": len(self._handlers),
            "contexts": self.context.count(),
            "links": len(self.tracker.links()),
        }

    def recent_messages(self, limit: int = 30) -> list[dict]:
        return [message.to_dict() for message in self.bus.history(limit)]
