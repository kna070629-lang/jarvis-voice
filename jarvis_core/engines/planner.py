"""jarvis_core.engines.planner — The task planning engine.

The planner breaks a request into tasks and works out which agent should take
each one. It never runs anything itself. The rules are deliberately small and
local so a simple request does not need a model to be decomposed.
"""

from __future__ import annotations

import re
import time
from dataclasses import dataclass, field

from ..graph import Task, TaskGraph


@dataclass
class Plan:
    """The result of planning a request."""

    graph: TaskGraph
    root_task_id: str
    summary: str = ""
    source: str = "rules"
    notes: list[str] = field(default_factory=list)


# The local rules, ordered from the most specific to the least.
_RULES: list[tuple[str, str]] = [
    # (task type, pattern) -- the agent is chosen by task type below.
    ("quality", r"^(?:run\s+)?(?:the\s+)?(?:backend\s+)?(?:tests?|test\s+suite|quality\s+check)\b.*$"),
    ("notify", r"^(?:read|show|check|summari[sz]e)\s+(?:my\s+|the\s+)?notifications?\.?$"),
    ("alarm", r"^(?:set|create)\s+(?:an?\s+)?alarm(?:\s+for)?\s+(.+?)\.?\s*$"),
    ("timer", r"^(?:set|start)\s+(?:a\s+)?timer\s+(?:for\s+)?(\d+)\s*(?:minutes?|mins?|m)\.?\s*$"),
    ("call", r"^(?:call|phone|ring)\s+(.+?)\.?\s*$"),
    ("message", r"^(?:send|prepare|draft)\s+(?:a\s+)?(?:whatsapp\s+message|sms|text(?:\s+message)?)\s+to\s+(.+?)\.?\s*$"),
    ("launch", r"^(?:open|launch|start|activate|run)\s+(.+?)\.?\s*$"),
    ("volume", r"^(?:(?:turn|make)\s+(?:the\s+)?volume\s+(up|down)|volume\s+(up|down|mute|unmute|max|[0-9]{1,3}))\.?\s*$"),
    ("media", r"^(play|pause|resume|stop|skip|next|previous|prev)\s+(?:the\s+)?(?:music|song|media|track|video)\.?\s*$"),
    ("flashlight", r"^(?:turn\s+(on|off)\s+(?:the\s+)?)?(?:flashlight|torch|flash\s+light)(?:\s+(on|off))?\.?\s*$"),
    ("research", r"^(?:research|look\s+up|search\s+for|find\s+out)\s+(.+?)\.?\s*$"),
    ("knowledge", r"^(?:what\s+do\s+you\s+know\s+about|explain|who\s+is|what\s+is)\s+(.+?)\.?\s*$"),
    ("development", r"^(?:review|analyse|analyze|debug|write|refactor|test)\s+(?:the\s+)?(?:code|codebase|script|repo|repository|function|bug)\b.*$"),
    ("time", r"^(?:what(?:'s| is)?\s+)?(?:the\s+)?(?:current\s+)?time.*$"),
    ("date", r"^(?:what(?:'s| is)?\s+)?(?:the\s+)?(?:current\s+)?(?:date|today).*$"),
]


# The agent that takes each kind of task.
_AGENT_FOR_TYPE = {
    "quality": "cluster.qa",
    "notify": "cluster.device",
    "alarm": "cluster.device",
    "timer": "cluster.device",
    "call": "cluster.device",
    "message": "cluster.device",
    "launch": "cluster.device",
    "volume": "cluster.device",
    "media": "cluster.device",
    "flashlight": "cluster.device",
    "time": "cluster.voice",
    "date": "cluster.voice",
    "research": "cluster.research",
    "knowledge": "cluster.knowledge",
    "development": "cluster.development",
    "automation": "cluster.automation",
}


class PlannerEngine:
    """Turns a request into a task graph."""

    def __init__(self, memory=None, model_router=None):
        self.memory = memory
        self.model_router = model_router

    def plan(self, request: str, context_reference: str = "") -> Plan:
        """Plan a request into tasks."""
        text = " ".join(str(request).split()).strip()
        root_id = "root"
        graph = TaskGraph(root=root_id)

        if not text:
            graph.create("answer", "Nothing to do", agent_id="cluster.voice")
            return Plan(graph=graph, root_task_id=root_id, summary="empty request")

        for task_type, pattern in _RULES:
            match = re.match(pattern, text, re.IGNORECASE)
            if not match:
                continue
            payload = {"request": text}
            # A rule can capture the argument in different groups, so the
            # first one that actually matched is used.
            argument = ""
            for group in match.groups():
                if group and group.strip():
                    argument = group.strip()
                    break
            if argument:
                payload["argument"] = argument
            agent_id = _AGENT_FOR_TYPE.get(task_type, "cluster.voice")
            task = graph.create(
                task_type=task_type,
                description=text,
                agent_id=agent_id,
                payload=payload,
                priority=2,
            )
            # A research or knowledge task needs the context first.
            if task_type in ("research", "knowledge", "development"):
                gather = graph.create(
                    task_type="gather_context",
                    description="Gather the relevant context",
                    agent_id="engine.memory",
                    depends_on=[],
                    priority=3,
                )
                task.depends_on.append(gather.task_id)
            return Plan(
                graph=graph,
                root_task_id=root_id,
                summary=f"one {task_type} task",
                source="rules",
            )

        # Nothing matched, so the model is asked to answer. The task is still
        # planned locally so the routing stays visible.
        task = graph.create(
            task_type="answer",
            description=text,
            agent_id="cluster.voice",
            payload={"request": text},
            priority=2,
        )
        return Plan(graph=graph, root_task_id=root_id, summary="free conversation", source="rules")

    def capabilities(self) -> list[str]:
        return sorted({task_type for task_type, _ in _RULES})
