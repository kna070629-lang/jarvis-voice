"""jarvis_core.messages — The structured message envelope.

Every communication inside JARVIS uses this envelope. The fields are fixed so
that an agent can be reasoned about from a log, and so that the interface can
show what actually happened without inventing anything.
"""

from __future__ import annotations

import json
import time
import uuid
from dataclasses import dataclass, field
from typing import Any


class Status:
    """The status values a message or a node can hold."""

    OFFLINE = "offline"
    IDLE = "idle"
    PENDING = "pending"
    LISTENING = "listening"
    THINKING = "thinking"
    PROCESSING = "processing"
    EXECUTING = "executing"
    WAITING_APPROVAL = "waiting_approval"
    COMPLETED = "completed"
    FAILED = "failed"
    CANCELLED = "cancelled"

    TERMINAL = {COMPLETED, FAILED, CANCELLED}


@dataclass
class Message:
    """One structured message between two parts of the system."""

    task_id: str
    agent_id: str
    source: str
    destination: str
    task_type: str
    context_reference: str = ""
    permissions: list[str] = field(default_factory=list)
    status: str = Status.PENDING
    result: Any = None
    evidence: list[dict] = field(default_factory=list)
    payload: dict = field(default_factory=dict)
    timestamp: float = field(default_factory=time.time)
    id: str = field(default_factory=lambda: uuid.uuid4().hex)
    parent_task_id: str = ""
    priority: int = 5
    error: str = ""
    duration_ms: int = 0

    def to_dict(self) -> dict:
        return {
            "task_id": self.task_id,
            "agent_id": self.agent_id,
            "source": self.source,
            "destination": self.destination,
            "task_type": self.task_type,
            "context_reference": self.context_reference,
            "permissions": list(self.permissions),
            "status": self.status,
            "result": self.result,
            "evidence": list(self.evidence),
            "payload": dict(self.payload),
            "timestamp": self.timestamp,
            "id": self.id,
            "parent_task_id": self.parent_task_id,
            "priority": self.priority,
            "error": self.error,
            "duration_ms": self.duration_ms,
        }

    @classmethod
    def from_dict(cls, value: dict) -> "Message":
        return cls(
            task_id=str(value.get("task_id", "")),
            agent_id=str(value.get("agent_id", "")),
            source=str(value.get("source", "")),
            destination=str(value.get("destination", "")),
            task_type=str(value.get("task_type", "")),
            context_reference=str(value.get("context_reference", "")),
            permissions=list(value.get("permissions") or []),
            status=str(value.get("status", Status.PENDING)),
            result=value.get("result"),
            evidence=list(value.get("evidence") or []),
            payload=dict(value.get("payload") or {}),
            timestamp=float(value.get("timestamp", time.time())),
            id=str(value.get("id", uuid.uuid4().hex)),
            parent_task_id=str(value.get("parent_task_id", "")),
            priority=int(value.get("priority", 5)),
            error=str(value.get("error", "")),
            duration_ms=int(value.get("duration_ms", 0)),
        )

    def to_json(self) -> str:
        return json.dumps(self.to_dict(), ensure_ascii=False, default=str)

    def copy(self) -> "Message":
        return Message.from_dict(self.to_dict())

    def is_terminal(self) -> bool:
        return self.status in Status.TERMINAL


@dataclass
class NodeState:
    """The live state of one node in the network."""

    node_id: str
    kind: str
    name: str
    cluster: str = ""
    role: str = ""
    status: str = Status.IDLE
    detail: str = ""
    capabilities: list[str] = field(default_factory=list)
    tasks_completed: int = 0
    tasks_failed: int = 0
    last_task_id: str = ""
    last_error: str = ""
    recent_tasks: list[dict] = field(default_factory=list)
    position: list[float] = field(default_factory=list)

    def to_dict(self) -> dict:
        return {
            "node_id": self.node_id,
            "kind": self.kind,
            "name": self.name,
            "cluster": self.cluster,
            "role": self.role,
            "status": self.status,
            "detail": self.detail,
            "capabilities": list(self.capabilities),
            "tasks_completed": self.tasks_completed,
            "tasks_failed": self.tasks_failed,
            "last_task_id": self.last_task_id,
            "last_error": self.last_error,
            "recent_tasks": list(self.recent_tasks),
            "position": list(self.position),
        }


@dataclass
class LinkState:
    """The live state of one connection between two nodes."""

    source: str
    destination: str
    status: str = "inactive"
    transmissions: int = 0
    failures: int = 0
    last_message_id: str = ""
    last_task_id: str = ""

    def to_dict(self) -> dict:
        return {
            "source": self.source,
            "destination": self.destination,
            "status": self.status,
            "transmissions": self.transmissions,
            "failures": self.failures,
            "last_message_id": self.last_message_id,
            "last_task_id": self.last_task_id,
        }
