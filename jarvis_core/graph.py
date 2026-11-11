"""jarvis_core.graph — The task dependency graph.

The planner turns a request into tasks. The graph records which task depends
on which, and the orchestrator walks it in dependency order so a task never
runs before the task it needs.
"""

from __future__ import annotations

import threading
import uuid
from dataclasses import dataclass, field


@dataclass
class Task:
    """One unit of work in a plan."""

    task_id: str
    task_type: str
    description: str
    agent_id: str = ""
    parent_task_id: str = ""
    payload: dict = field(default_factory=dict)
    depends_on: list[str] = field(default_factory=list)
    permissions: list[str] = field(default_factory=list)
    status: str = "pending"
    result: object = None
    error: str = ""
    requires_approval: bool = False
    priority: int = 5

    def to_dict(self) -> dict:
        return {
            "task_id": self.task_id,
            "task_type": self.task_type,
            "description": self.description,
            "agent_id": self.agent_id,
            "depends_on": list(self.depends_on),
            "permissions": list(self.permissions),
            "status": self.status,
            "requires_approval": self.requires_approval,
            "priority": self.priority,
        }


class TaskGraph:
    """A dependency graph of tasks with a topological order."""

    def __init__(self, root: str = ""):
        self.root = root
        self._tasks: dict[str, Task] = {}
        self._lock = threading.RLock()

    def add(self, task: Task) -> Task:
        with self._lock:
            self._tasks[task.task_id] = task
        return task

    def create(
        self,
        task_type: str,
        description: str,
        agent_id: str = "",
        depends_on: list[str] | None = None,
        permissions: list[str] | None = None,
        payload: dict | None = None,
        priority: int = 5,
    ) -> Task:
        task = Task(
            task_id=uuid.uuid4().hex[:12],
            task_type=task_type,
            description=description,
            agent_id=agent_id,
            depends_on=list(depends_on or []),
            permissions=list(permissions or []),
            payload=dict(payload or {}),
            priority=priority,
        )
        return self.add(task)

    def get(self, task_id: str) -> Task | None:
        with self._lock:
            return self._tasks.get(task_id)

    def all(self) -> list[Task]:
        with self._lock:
            return list(self._tasks.values())

    def ready(self) -> list[Task]:
        """The tasks whose dependencies are all satisfied."""
        with self._lock:
            ready = []
            for task in self._tasks.values():
                if task.status != "pending":
                    continue
                if all(self._tasks.get(dep) and self._tasks[dep].status == "completed"
                       for dep in task.depends_on):
                    ready.append(task)
            ready.sort(key=lambda item: (-item.priority, item.created if hasattr(item, "created") else 0))
            return ready

    def detect_cycles(self) -> list[str]:
        """Return the task identifiers that take part in a dependency cycle."""
        with self._lock:
            visiting: set[str] = set()
            done: set[str] = set()
            cycles: list[str] = []

            def walk(node_id: str, path: list[str]) -> None:
                if node_id in done:
                    return
                if node_id in visiting:
                    cycles.extend(path[path.index(node_id):])
                    return
                visiting.add(node_id)
                task = self._tasks.get(node_id)
                if task is not None:
                    for dependency in task.depends_on:
                        walk(dependency, path + [node_id])
                visiting.discard(node_id)
                done.add(node_id)

            for task_id in self._tasks:
                walk(task_id, [])
            return cycles

    def to_dict(self) -> dict:
        return {
            "root": self.root,
            "tasks": [task.to_dict() for task in self.all()],
        }
