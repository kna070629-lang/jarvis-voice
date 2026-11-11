"""jarvis_core.registry — The agent registry.

An agent is a named worker with declared capabilities. The registry is the
only way the core finds an agent, and the only way an agent is handed work.
Agents are never looked up dynamically from arbitrary names, so a model cannot
invent a destination.
"""

from __future__ import annotations

import threading
import time
from typing import Callable

from .messages import Message, NodeState, Status


class Agent:
    """A worker that can be given a message and returns a message."""

    #: The stable identifier, for example "cluster.device".
    id: str = ""
    #: The display name.
    name: str = ""
    #: Which cluster or engine the agent belongs to.
    cluster: str = ""
    #: The kinds of task the agent accepts.
    capabilities: list[str] = []
    #: The permissions the agent needs. The security engine checks these.
    permissions: list[str] = []

    #: The specialist role this agent plays, for example ATLAS or VEGA.
    role: str = ""

    def handle(self, message: Message, context) -> Message:
        """Process a message. Subclasses do the real work."""
        raise NotImplementedError


class AgentRegistry:
    """Holds every agent and the live state of every node."""

    def __init__(self):
        self._agents: dict[str, Agent] = {}
        self._states: dict[str, NodeState] = {}
        self._lock = threading.RLock()
        self._listeners: list[Callable[[str, Message], None]] = []

    # -- registration -----------------------------------------------------
    def register(self, agent: Agent) -> Agent:
        with self._lock:
            self._agents[agent.id] = agent
            self._states[agent.id] = NodeState(
                node_id=agent.id,
                kind="agent",
                name=agent.name or agent.id,
                cluster=agent.cluster,
                role=getattr(agent, "role", "") or "",
                status=Status.IDLE,
                capabilities=list(agent.capabilities),
            )
        return agent

    def register_all(self, agents: list[Agent]) -> None:
        for agent in agents:
            self.register(agent)

    def ensure_node(
        self,
        node_id: str,
        name: str,
        kind: str = "node",
        cluster: str = "",
        capabilities: list[str] | None = None,
    ) -> NodeState:
        """Make sure a node has a state, even when it is not an agent.

        The central core is a node rather than an agent, but its state still
        has to be visible in the network.
        """
        with self._lock:
            state = self._states.get(node_id)
            if state is None:
                state = NodeState(
                    node_id=node_id,
                    kind=kind,
                    name=name,
                    cluster=cluster,
                    status=Status.IDLE,
                    capabilities=list(capabilities or []),
                )
                self._states[node_id] = state
            return state

    # -- lookup -----------------------------------------------------------
    def get(self, agent_id: str) -> Agent | None:
        with self._lock:
            return self._agents.get(agent_id)

    def all(self) -> list[Agent]:
        with self._lock:
            return list(self._agents.values())

    def by_cluster(self, cluster: str) -> list[Agent]:
        with self._lock:
            return [agent for agent in self._agents.values() if agent.cluster == cluster]

    def find_by_capability(self, capability: str) -> list[Agent]:
        with self._lock:
            return [agent for agent in self._agents.values() if capability in agent.capabilities]

    def has(self, agent_id: str) -> bool:
        with self._lock:
            return agent_id in self._agents

    # -- state ------------------------------------------------------------
    def state(self, node_id: str) -> NodeState | None:
        with self._lock:
            return self._states.get(node_id)

    def set_status(
        self,
        node_id: str,
        status: str,
        detail: str = "",
        task_id: str = "",
        error: str = "",
    ) -> None:
        with self._lock:
            state = self._states.get(node_id)
            if state is None:
                return
            state.status = status
            if detail:
                state.detail = detail
            if task_id:
                state.last_task_id = task_id
            if error:
                state.last_error = error

    def record_completion(self, node_id: str, task_id: str, ok: bool, summary: str) -> None:
        with self._lock:
            state = self._states.get(node_id)
            if state is None:
                return
            if ok:
                state.tasks_completed += 1
            else:
                state.tasks_failed += 1
            state.last_task_id = task_id
            state.recent_tasks.insert(0, {
                "task_id": task_id,
                "ok": ok,
                "summary": summary[:200],
                "at": time.time(),
            })
            del state.recent_tasks[8:]

    def states(self) -> list[NodeState]:
        with self._lock:
            return list(self._states.values())

    # -- observation ------------------------------------------------------
    def add_listener(self, listener: Callable[[str, Message], None]) -> None:
        """Watch every message that passes through the registry."""
        with self._lock:
            self._listeners.append(listener)

    def notify(self, message: Message) -> None:
        with self._lock:
            listeners = list(self._listeners)
        for listener in listeners:
            try:
                listener(message.source, message)
            except Exception:
                # A broken observer must never break the system.
                continue
